package io.archsim.engine.components.service;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.NodeSpec;

/**
 * A service with a fixed number of replicas during a run: each replica is a pool of
 * handlers with its own queue, sent requests in turn ({@link Serving}), its CPU work
 * stalled by its garbage collector ({@link GarbageCollector}). Its report adds its
 * cores', memory's and pauses' figures and ceilings ({@link ServiceReport}) and formulas
 * ({@link ServiceExplain}). Autoscaling, serverless and Kubernetes services come with
 * their slice of the port.
 */
public final class ServiceRuntime extends RuntimeComponent {

	private final ServiceState state;

	private final Serving serving;

	private final Sampler serviceTime;

	private final PythonRandom random;

	private int next;

	/**
	 * A service at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public ServiceRuntime(SimContext context, NodeSpec node, ServiceConfig config) {
		super(context, node, OptionalInt.of(config.replicas() * config.handlersPerReplica()));
		this.state = new ServiceState(context, node, metrics(), config);
		this.serviceTime = Sampler.of(config.serviceTimeMs());
		this.random = context.stream("node:" + node.id() + ":service");
		GarbageCollector collector = new GarbageCollector(context.simulation(), this.state,
				context.stream("node:" + node.id() + ":gc"));
		this.serving = new Serving(context, this.state, collector, this::sampleServiceTime);
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		// called without a load balancer: the replicas take turns
		List<Replica> replicas = this.state.replicas();
		int index = this.next;
		this.next = (index + 1) % replicas.size();
		this.serving.serve(request, replicas.get(index), done);
	}

	@Override
	public boolean splitsCpu() {
		return true;
	}

	@Override
	public SequencedMap<String, Double> resourceCapacity(OptionalDouble holdMs, OptionalDouble serviceMs) {
		return ServiceReport.resourceCapacity(this.state, holdMs, serviceMs);
	}

	@Override
	public double slotCapacity(double slots, double holdMs, OptionalDouble serviceMs) {
		if (this.state.curve().isEmpty() || serviceMs.orElse(0.0) <= 0) {
			return super.slotCapacity(slots, holdMs, serviceMs);
		}
		return ServiceReport.slotCapacity(this.state, slots, holdMs, serviceMs.getAsDouble());
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return ServiceReport.extraSummary(this.state);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return ServiceReport.extraTimeseries(this.state);
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return ServiceExplain.of(this.state, summary);
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return ServiceReport.behaviour(this.state);
	}

	@Override
	public void finish(double end) {
		super.finish(end);
		this.state.finish(end);
	}

	private double sampleServiceTime() {
		return sampleTime(this.serviceTime, this.random);
	}

}
