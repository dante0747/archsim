package io.archsim.engine.components.loadbalancer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.components.loadbalancer.Routing.Candidate;
import io.archsim.engine.explain.Formula;
import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.Calls.Call;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.Request.Interval;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.TokenBucket;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.document.NodeSpec;

/**
 * A load balancer during a run: it refuses requests beyond its connections and its rate
 * limit, adds its overhead, and forwards each request to an instance of its targets
 * ({@link Routing}), giving up on a target after the link's timeout, or after its own
 * upstream timeout when that is shorter (504), answering 503 for a target with as many
 * requests in flight as its circuit breaker allows, and retrying as the link allows, on
 * another instance when there is one. Its utilization is its requests in flight over its
 * connections.
 */
public final class LoadBalancerRuntime extends RuntimeComponent {

	/** What each technology brings, read once from the component catalog. */
	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog
		.technologyTraits(LoadBalancerSpec.TYPE);

	/** The counter of requests a circuit breaker answered 503. */
	private static final String CIRCUIT_OPEN = "circuitOpen";

	/** The counter of requests given up on after the upstream timeout. */
	private static final String UPSTREAM_TIMEOUTS = "upstreamTimeouts";

	/** The counter of requests with no instance taking requests behind the balancer. */
	private static final String NO_READY_TARGETS = "noReadyTargets";

	private final LoadBalancerConfig config;

	private final @Nullable Double upstreamTimeoutMs;

	private final @Nullable Integer breaker;

	private final @Nullable TokenBucket rateLimit;

	private final Routing routing;

	private final Sampler overhead;

	private final PythonRandom overheadRandom;

	/** Requests in flight to each target, by its id. */
	private final Map<String, Integer> active = new HashMap<>();

	/**
	 * A balancer at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public LoadBalancerRuntime(SimContext context, NodeSpec node, LoadBalancerConfig config) {
		super(context, node, OptionalInt.of(config.maxConnections()));
		this.config = config;
		this.upstreamTimeoutMs = config.effectiveUpstreamTimeoutMs().orElse(null);
		this.breaker = config.effectiveCircuitBreakerMaxRequests().orElse(null);
		Double rate = config.rateLimitRps();
		this.rateLimit = (rate != null) ? TokenBucket.of(rate, config.rateLimitBurst()) : null;
		this.routing = new Routing(context, node.id(), config.policy(), context.stream("node:" + node.id() + ":lb"));
		this.overhead = Sampler.of(config.overheadMs());
		this.overheadRandom = context.stream("node:" + node.id() + ":overhead");
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		double now = now();
		metrics().arrive(now);
		if (metrics().inflight().value() > this.config.maxConnections()) {
			done.accept(refuse(FailureReason.MAX_CONNECTIONS, now));
			return;
		}
		if (this.rateLimit != null && !this.rateLimit.tryTake(now, 1.0)) {
			done.accept(refuse(FailureReason.RATE_LIMITED, now));
			return;
		}
		double overheadMs = sampleTime(this.overhead, this.overheadRandom);
		simulation().after(overheadMs, () -> {
			metrics().service().record(now(), overheadMs);
			new Forwarding(request, now, done).next();
		});
	}

	@Override
	public Gauge occupancy() {
		return metrics().inflight();
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		SequencedMap<String, List<@Nullable Double>> extra = new LinkedHashMap<>();
		extra.put("inflightMean", series(metrics().inflight().meanSeries()));
		Counter rateLimited = metrics().rejected().get(FailureReason.RATE_LIMITED);
		if (rateLimited != null) {
			extra.put("rateLimitedRps", series(rateLimited.rateSeries()));
		}
		Counter upstreamTimeouts = metrics().counters().get(UPSTREAM_TIMEOUTS);
		if (upstreamTimeouts != null) {
			extra.put("upstreamTimeoutRps", series(upstreamTimeouts.rateSeries()));
		}
		Counter circuitOpen = metrics().counters().get(CIRCUIT_OPEN);
		if (circuitOpen != null) {
			extra.put("circuitOpenRps", series(circuitOpen.rateSeries()));
		}
		return extra;
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return LoadBalancerExplain.of(this.config);
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		Map<String, Boolean> inEffect = Map.of("circuit_breakers", this.breaker != null, "account_throttle",
				this.rateLimit != null);
		return TRAITS.getOrDefault(this.config.technology().json(), List.of())
			.stream()
			.filter((trait) -> inEffect.getOrDefault(trait.id(), true))
			.toList();
	}

	private Outcome refuse(FailureReason reason, double now) {
		metrics().reject(reason, now);
		return Outcome.rejected(reason, id());
	}

	/**
	 * One request's way through the balancer: to an instance, again to another when the
	 * link allows a retry, until one answers or the attempts run out.
	 */
	private final class Forwarding {

		private final Request request;

		private final double start;

		private final Consumer<Outcome> done;

		private Outcome outcome = Outcome.OK;

		private @Nullable Candidate avoid;

		private int attempts;

		Forwarding(Request request, double start, Consumer<Outcome> done) {
			this.request = request;
			this.start = start;
			this.done = done;
		}

		void next() {
			Optional<Candidate> found = LoadBalancerRuntime.this.routing.pick(this.request.operation(), this.avoid);
			if (found.isEmpty()) {
				answerNoReadyTarget();
				finish();
				return;
			}
			Candidate picked = found.get();
			String targetId = picked.edge().targetId();
			Integer limit = LoadBalancerRuntime.this.breaker;
			if (limit != null && LoadBalancerRuntime.this.active.getOrDefault(targetId, 0) >= limit) {
				answerUnavailable();
				this.attempts++;
				if (exhausted(picked)) {
					finish();
					return;
				}
				this.avoid = picked;
				next();
				return;
			}
			forward(picked);
		}

		private void forward(Candidate picked) {
			String targetId = picked.edge().targetId();
			LoadBalancerRuntime.this.active.merge(targetId, 1, Integer::sum);
			context().calls().once(this.request, call(picked), (reply) -> {
				LoadBalancerRuntime.this.active.merge(targetId, -1, Integer::sum);
				answered(picked, reply);
			});
		}

		// done, or a retry on another instance after the link's backoff
		private void answered(Candidate picked, Outcome answer) {
			if (answer.failedFor(FailureReason.GATEWAY_TIMEOUT)) {
				metrics().count(UPSTREAM_TIMEOUTS, now());
			}
			this.attempts++;
			this.outcome = answer;
			if (answer.ok() || exhausted(picked)) {
				finish();
				return;
			}
			picked.edge().retries().increment(now());
			this.avoid = picked;
			simulation().after(picked.edge().backoffMs(this.attempts), this::next);
		}

		// the link's timeout, or the balancer's upstream timeout when it is shorter (504)
		private Call call(Candidate picked) {
			Double timeoutMs = picked.edge().config().timeoutMs();
			FailureReason reason = FailureReason.DEPENDENCY_TIMEOUT;
			Double upstream = LoadBalancerRuntime.this.upstreamTimeoutMs;
			if (upstream != null && (timeoutMs == null || upstream < timeoutMs)) {
				timeoutMs = upstream;
				reason = FailureReason.GATEWAY_TIMEOUT;
			}
			return new Call(picked.edge(), (request, reply) -> picked.target().handleOn(request, picked.index(), reply),
					timeoutMs, id(), reason);
		}

		private boolean exhausted(Candidate picked) {
			return this.attempts >= picked.edge().config().maxAttempts();
		}

		// no instance behind the balancer takes requests: 503
		private void answerNoReadyTarget() {
			double now = now();
			metrics().count(NO_READY_TARGETS, now);
			this.request.span(id(), SpanKind.REJECTED, new Interval(now, now),
					"no ready instance behind the balancer (503)");
			this.outcome = Outcome.rejected(FailureReason.NO_READY_PODS, id());
		}

		// the target's circuit breaker is open: 503 for this attempt
		private void answerUnavailable() {
			double now = now();
			metrics().count(CIRCUIT_OPEN, now);
			this.request.span(id(), SpanKind.REJECTED, new Interval(now, now), "circuit breaker overflow (503)");
			this.outcome = Outcome.rejected(FailureReason.CIRCUIT_OPEN, id());
		}

		private void finish() {
			double now = now();
			metrics().hold().record(now, now - this.start);
			metrics().depart(now, this.outcome, this.request);
			this.done.accept(this.outcome);
		}

	}

}
