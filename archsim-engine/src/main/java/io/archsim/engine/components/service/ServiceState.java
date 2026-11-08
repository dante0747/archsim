package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.CpuMachine;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.GcPauses;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.component.service.Warmup;
import io.archsim.model.document.NodeSpec;

/**
 * A service's state during a run, as its handling and its report share it: its settings
 * in effect, its replicas, and what it measures beyond what every part does (busy cores,
 * warming replicas, pause time, the load factors its requests were served at, the most
 * requests a replica held).
 */
final class ServiceState {

	/** What each technology brings, read once from the component catalog. */
	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog
		.technologyTraits(ServiceSpec.TYPE);

	private final String nodeId;

	private final NodeMetrics metrics;

	private final ServiceConfig config;

	private final List<TechnologyTrait> traits;

	private final int perReplica;

	private final @Nullable GcPauses gc;

	private final @Nullable Warmup warmup;

	private final LoadCurve curve;

	private final OptionalInt memorySlots;

	private final @Nullable Gauge coreBusy;

	private final Gauge warming;

	private final double[] pauseMs;

	private final @Nullable MeanAccumulator factors;

	private final List<Replica> replicas = new ArrayList<>();

	private int peakLoad;

	/**
	 * A service's state at the start of a run, its replicas present and warm.
	 * @param context the run
	 * @param node the service
	 * @param metrics its metrics
	 * @param config its settings
	 */
	ServiceState(SimContext context, NodeSpec node, NodeMetrics metrics, ServiceConfig config) {
		this.nodeId = node.id();
		this.metrics = metrics;
		this.config = config;
		this.traits = TRAITS.getOrDefault(config.technology().json(), List.of());
		this.perReplica = config.handlersPerReplica();
		this.gc = config.gcPauses().orElse(null);
		this.warmup = config.warmup().orElse(null);
		this.curve = LoadCurve.of(config.serviceTimeByLoad());
		this.memorySlots = config.memorySlots();
		this.coreBusy = (config.cpuCores() != null) ? new Gauge(context.timeline()) : null;
		this.warming = new Gauge(context.timeline());
		this.pauseMs = new double[context.timeline().buckets()];
		this.factors = this.curve.isEmpty() ? null : new MeanAccumulator(context.timeline());
		WorkerPool.Queue queue = new WorkerPool.Queue(config.queueCapacity(), config.queueTimeoutMs());
		for (int index = 0; index < config.replicas(); index++) {
			this.replicas.add(new Replica(new WorkerPool(context.simulation(), this.perReplica, queue, metrics),
					newCores(context), this.curve.isEmpty() ? null : new RateMeter(0.0)));
		}
	}

	// a replica's CPU, on its machine: its own hyperthreads, the design's host the
	// replicas share, or full cores
	private Cores newCores(SimContext context) {
		Cores.Accounts accounts = new Cores.Accounts(this.coreBusy,
				context.machineFor(this.config.cpuCores(), this.config.siblingSpeed()));
		return new Cores(context.simulation(), this.config.cpuCores(), this.perReplica, accounts);
	}

	String nodeId() {
		return this.nodeId;
	}

	NodeMetrics metrics() {
		return this.metrics;
	}

	ServiceConfig config() {
		return this.config;
	}

	List<TechnologyTrait> traits() {
		return this.traits;
	}

	int perReplica() {
		return this.perReplica;
	}

	@Nullable GcPauses gc() {
		return this.gc;
	}

	@Nullable Warmup warmup() {
		return this.warmup;
	}

	LoadCurve curve() {
		return this.curve;
	}

	OptionalInt memorySlots() {
		return this.memorySlots;
	}

	@Nullable Gauge coreBusy() {
		return this.coreBusy;
	}

	Gauge warming() {
		return this.warming;
	}

	/**
	 * The machine the replicas' CPU work runs on.
	 * @return the design's host, the first replica's own hyperthreads, or full cores
	 */
	CpuMachine machine() {
		return this.replicas.getFirst().cores().machine();
	}

	List<Replica> replicas() {
		return Collections.unmodifiableList(this.replicas);
	}

	/**
	 * The replicas taking requests, on average over the run.
	 * @return the fixed count
	 */
	double replicasMean() {
		return this.replicas.size();
	}

	/**
	 * The most requests one replica held at once.
	 * @return the count
	 */
	int peakLoad() {
		return this.peakLoad;
	}

	void notePeakLoad(int load) {
		this.peakLoad = Math.max(this.peakLoad, load);
	}

	/**
	 * The time the replicas spent paused in each time bucket, over the replicas.
	 * @return milliseconds per bucket
	 */
	double[] pauseMs() {
		return this.pauseMs.clone();
	}

	void addPause(double time, double durationMs) {
		this.pauseMs[this.metrics.timeline().index(time)] += durationMs / Math.max(1, this.replicas.size());
	}

	void recordFactor(double time, double factor) {
		if (this.factors != null) {
			this.factors.record(time, factor);
		}
	}

	/**
	 * The mean service time at factor 1: the window's mean over the mean load factor its
	 * requests were served at.
	 * @param serviceMs the window's mean service time
	 * @return the time at factor 1; the mean itself without a load curve
	 */
	double baseServiceMs(double serviceMs) {
		MeanAccumulator served = this.factors;
		OptionalDouble meanFactor = (served != null) ? served.windowMean() : OptionalDouble.empty();
		return (meanFactor.isPresent() && meanFactor.getAsDouble() != 0) ? serviceMs / meanFactor.getAsDouble()
				: serviceMs;
	}

	void finish(double end) {
		this.warming.finish(end);
		if (this.coreBusy != null) {
			this.coreBusy.finish(end);
		}
	}

}
