package io.archsim.engine.components.service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.DependencyLinks;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.Request.Interval;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.Warmup;
import io.archsim.model.document.NodeSpec;

/**
 * A serverless function during a run: each request takes an instance of its own
 * ({@link FunctionInstances}), waits for a new instance's cold start, is served (slower
 * while the instance warms up, and on a fraction of a vCPU), then calls the function's
 * dependencies in order. Its report adds its cold starts and instances
 * ({@link ServerlessReport}).
 */
public final class ServerlessRuntime extends RuntimeComponent {

	/** The counter of instances started for a request. */
	static final String COLD_STARTS = "coldStarts";

	private final ServiceConfig config;

	private final FunctionInstances instances;

	private final DependencyLinks dependencies;

	private final Sampler serviceTime;

	private final Sampler coldStart;

	private final PythonRandom random;

	private final PythonRandom coldRandom;

	private final @Nullable Warmup warmup;

	private final LoadCurve curve;

	private final @Nullable RateMeter meter;

	private long coldStarts;

	/**
	 * A function at the start of a run, with no instance.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public ServerlessRuntime(SimContext context, NodeSpec node, ServiceConfig config) {
		super(context, node, OptionalInt.of(config.concurrencyLimit()));
		this.config = config;
		this.random = context.stream("node:" + node.id() + ":service");
		this.coldRandom = context.stream("node:" + node.id() + ":cold");
		this.instances = new FunctionInstances(context.simulation(), context.timeline(), config);
		this.dependencies = new DependencyLinks(context, node.id());
		this.serviceTime = Sampler.of(config.serviceTimeMs());
		this.coldStart = Sampler.of(config.coldStartInEffect());
		this.warmup = config.warmup().orElse(null);
		this.curve = LoadCurve.of(config.serviceTimeByLoad());
		this.meter = this.curve.isEmpty() ? null : new RateMeter(0.0);
		this.instances.startReclaiming();
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		double now = now();
		metrics().arrive(now);
		if (this.meter != null) {
			this.meter.arrive(now);
		}
		Optional<FunctionInstances.Instance> taken = this.instances.take(now);
		if (taken.isEmpty()) {
			throttle(request, done);
			return;
		}
		Invocation invocation = new Invocation(request, taken.get(), now, done);
		metrics().busy().add(now, 1);
		if (invocation.instance().cold()) {
			startInstance(invocation);
		}
		else {
			work(invocation);
		}
	}

	@Override
	public boolean splitsCpu() {
		return true;
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return ServerlessReport.extraSummary(this.coldStarts, this.instances);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return ServerlessReport.extraTimeseries(metrics(), this.instances);
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return List.of(ServerlessReport.concurrency(this.config, summary));
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return ServerlessReport.behaviour(this.config);
	}

	@Override
	public void finish(double end) {
		super.finish(end);
		this.instances.finish(end);
	}

	private void throttle(Request request, Consumer<Outcome> done) {
		double now = now();
		metrics().reject(FailureReason.THROTTLED, now);
		request.span(id(), SpanKind.REJECTED, new Interval(now, now), "throttled: concurrency or scale-out rate");
		done.accept(Outcome.rejected(FailureReason.THROTTLED, id()));
	}

	// a new instance's cold start, before its first request
	private void startInstance(Invocation invocation) {
		this.coldStarts++;
		metrics().count(COLD_STARTS, invocation.start());
		double startupMs = this.coldStart.sample(this.coldRandom);
		simulation().after(startupMs, () -> {
			invocation.request()
				.span(id(), SpanKind.COLD_START, new Interval(invocation.start(), now()), "starting a new instance");
			work(invocation);
		});
	}

	// the service time on the instance, then the calls to the dependencies
	private void work(Invocation invocation) {
		double serviceMs = serviceMs(invocation.instance());
		metrics().cpu().add(now(), 1);
		simulation().after(cpuMs(serviceMs), () -> {
			double now = now();
			metrics().cpu().add(now, -1);
			metrics().service().record(now, serviceMs);
			context().calls()
				.dependencies(this.dependencies.carrying(invocation.request().operation()), invocation.request(), id(),
						(outcome) -> complete(invocation, outcome));
		});
	}

	// a sampled service time, slower at the function's load per busy instance, then
	// slower while the instance warms up
	private double serviceMs(FunctionInstances.Instance instance) {
		double now = now();
		double serviceMs = sampleTime(this.serviceTime, this.random);
		if (this.meter != null) {
			// the instance that finished last is reused first, so the function's rate is
			// spread over the instances busy now, not every one kept warm
			serviceMs *= this.curve.factorAt(this.meter.rps(now) / Math.max(1, metrics().busy().value()));
		}
		// as in the Python engine, an instance started at time 0 is warm
		double slowdown = (this.warmup != null && instance.born() > 0)
				? this.warmup.slowdownAfter(now - instance.born()) : 1.0;
		return serviceMs * slowdown;
	}

	// one request per instance: only a fraction of a vCPU (below 1) slows the CPU work,
	// and only the CPU share of the service time is CPU work
	private double cpuMs(double serviceMs) {
		Double cores = this.config.cpuCores();
		if (cores == null || cores >= 1) {
			return serviceMs;
		}
		double cpuWork = serviceMs * this.config.cpuShare();
		return cpuWork / cores + (serviceMs - cpuWork);
	}

	// the instance kept warm for the next request, the outcome counted and passed on
	private void complete(Invocation invocation, Outcome outcome) {
		double end = now();
		metrics().busy().add(end, -1);
		metrics().hold().record(end, end - invocation.start());
		this.instances.release(invocation.instance(), end);
		metrics().depart(end, outcome, invocation.request());
		invocation.done().accept(outcome);
	}

	/**
	 * A request on its instance.
	 *
	 * @param request the request
	 * @param instance its instance
	 * @param start when it took the instance
	 * @param done what to do with its outcome
	 */
	private record Invocation(Request request, FunctionInstances.Instance instance, double start,
			Consumer<Outcome> done) {
	}

}
