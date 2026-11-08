package io.archsim.engine.components.external;

import java.util.OptionalInt;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.TokenBucket;
import io.archsim.model.component.external.ExternalApiConfig;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * A third-party API during a run: each call takes a sampled latency and fails with the
 * provider's error rate; calls above its rate limit or concurrency limit fail at once, as
 * a provider answering 429 would. There is no queue on our side. A provider's failures
 * are its answers, so they count as failed calls, not as refusals.
 */
public final class ExternalApiRuntime extends RuntimeComponent {

	/** The counter of calls refused by the provider's rate limit. */
	private static final String RATE_LIMITED = "rateLimited";

	/** The counter of calls refused by the provider's concurrency limit. */
	private static final String THROTTLED = "throttled";

	/** The counter of calls the provider answered with an error. */
	private static final String UPSTREAM_ERRORS = "upstreamErrors";

	/** The counter of reads served. */
	private static final String READS = "reads";

	/** The counter of writes served. */
	private static final String WRITES = "writes";

	private final ExternalApiConfig config;

	private final Sampler latency;

	private final PythonRandom latencyStream;

	private final PythonRandom errorStream;

	private final @Nullable TokenBucket rateLimit;

	private int active;

	/**
	 * An API at the start of a run.
	 * @param context the run
	 * @param node the API
	 * @param config its settings
	 */
	public ExternalApiRuntime(SimContext context, NodeSpec node, ExternalApiConfig config) {
		super(context, node, slots(config.concurrencyLimit()));
		this.config = config;
		this.latency = Sampler.of(config.latencyMs());
		this.latencyStream = context.stream("node:" + node.id() + ":latency");
		this.errorStream = context.stream("node:" + node.id() + ":errors");
		Double rate = config.rateLimitRps();
		this.rateLimit = (rate != null) ? TokenBucket.ofRate(rate) : null;
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		double start = now();
		metrics().arrive(start);
		if (this.rateLimit != null && !this.rateLimit.tryTake(start, 1.0)) {
			done.accept(fail(request, FailureReason.RATE_LIMITED, RATE_LIMITED));
			return;
		}
		Integer limit = this.config.concurrencyLimit();
		if (limit != null && this.active >= limit) {
			done.accept(fail(request, FailureReason.THROTTLED, THROTTLED));
			return;
		}
		this.active++;
		metrics().busy().add(start, 1);
		double took = sampleTime(this.latency, this.latencyStream);
		if (took > 0) {
			simulation().timeout(took).then(() -> answer(request, start, took, done));
		}
		else {
			answer(request, start, took, done);
		}
	}

	private void answer(Request request, double start, double took, Consumer<Outcome> done) {
		double now = now();
		request.span(id(), SpanKind.EXTERNAL, new Request.Interval(start, now), "third-party call");
		metrics().busy().add(now, -1);
		this.active--;
		metrics().service().record(now, took);
		metrics().hold().record(now, now - start);
		if (this.config.errorRate() > 0 && this.errorStream.nextDouble() < this.config.errorRate()) {
			done.accept(fail(request, FailureReason.UPSTREAM_ERROR, UPSTREAM_ERRORS));
			return;
		}
		metrics().count((request.operation() == RequestType.READ) ? READS : WRITES, now);
		metrics().depart(now, Outcome.OK, request);
		done.accept(Outcome.OK);
	}

	private static OptionalInt slots(@Nullable Integer concurrencyLimit) {
		return (concurrencyLimit != null) ? OptionalInt.of(concurrencyLimit) : OptionalInt.empty();
	}

	private Outcome fail(Request request, FailureReason reason, String counter) {
		double now = now();
		metrics().count(counter, now);
		Outcome outcome = Outcome.rejected(reason, id());
		metrics().depart(now, outcome, request);
		return outcome;
	}

}
