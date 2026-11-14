package io.archsim.model.component.service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.codec.Ui;
import io.archsim.model.component.Resources;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.LogNormal;

/**
 * A service: its replicas and workers, how long a request takes on a worker, its
 * resources, and how it scales.
 *
 * @param technology the runtime or framework
 * @param platform where it runs
 * @param replicas instances (with autoscaling, the starting count)
 * @param workersPerReplica requests one replica handles at once
 * @param queueCapacity requests that may wait for a worker per replica
 * @param queueTimeoutMs how long a request may wait for a worker; {@code null}: no limit
 * @param serviceTimeMs handler time per request on a worker, downstream calls excluded
 * @param serviceTimeByLoad how the service time changes with load (schema 1.6); empty: it
 * does not
 * @param blockingDownstream whether a worker stays busy while it waits for downstream
 * calls
 * @param cpuCores vCPU per replica; {@code null}: not modelled
 * @param cpuShare the share of service time that needs a CPU core (schema 1.5)
 * @param siblingSpeed a vCPU's speed while its core's other vCPU is busy too (schema 1.7)
 * @param memoryGib memory per replica; {@code null}: not modelled
 * @param instanceType the instance type in the design's price snapshot (schema 1.4)
 * @param memoryPerRequestMb memory one in-flight request uses
 * @param memoryBaselineMb memory a replica uses before any request
 * @param memoryPerRpsMb memory that grows with throughput, per request per second (schema
 * 1.5)
 * @param autoscaling whether replicas are added and removed to keep utilization near the
 * target
 * @param minReplicas the fewest replicas autoscaling keeps
 * @param maxReplicas the most replicas autoscaling runs
 * @param targetUtilization the utilization autoscaling aims for
 * @param scaleIntervalS how often autoscaling re-evaluates
 * @param startupS the time until a new replica takes traffic
 * @param concurrencyLimit serverless: the most instances at once
 * @param coldStartMs serverless: the extra time to start an instance; {@code null}: the
 * runtime's
 * @param cluster the Kubernetes cluster it runs on; {@code null}: the design's only one
 * @param scaleOutRate serverless: new instances per 10 seconds
 * @param runtimeModel a thread per request, or event loops; auto follows the technology
 * @param processesPerReplica event-loop processes per replica
 * @param gcPauseMs the median stop-the-world GC pause; {@code null}: the runtime's
 * @param gcEveryRequests requests between collections; {@code null}: the runtime's
 * @param warmupFactor how much slower a new instance runs before the JIT warms up;
 * {@code null}: the runtime's
 * @param warmupS the time a new instance takes to reach full speed; {@code null}: the
 * runtime's
 * @param keepWarmS serverless: idle time after which an instance is reclaimed
 * @param costPerHour the price of one replica per hour, in USD
 */
public record ServiceConfig(
		@Default("generic") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) ServiceTechnology technology,
		@Default("vm") @Description(Descriptions.PLATFORM) @Ui(group = Descriptions.TECHNOLOGY_GROUP) Platform platform,
		@Default("2") @Description(Descriptions.REPLICAS) @Ui(group = Descriptions.CAPACITY_GROUP,
				headline = true) @Ge(1) @Le(500) int replicas,
		@Default("4") @Description(Descriptions.WORKERS_PER_REPLICA) @Ui(group = Descriptions.CAPACITY_GROUP,
				headline = true) @Ge(1) @Le(4096) int workersPerReplica,
		@Default("50") @Description(Descriptions.QUEUE_CAPACITY) @Ui(label = "Queue limit per replica",
				group = Descriptions.CAPACITY_GROUP) @Ge(0) @Le(100_000) int queueCapacity,
		@Default("1000.0") @Description(Descriptions.QUEUE_TIMEOUT_MS) @Ui(label = "Queue timeout", unit = "ms",
				group = Descriptions.CAPACITY_GROUP) @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,

		@DefaultFrom("DEFAULT_SERVICE_TIME") @Description(Descriptions.SERVICE_TIME_MS) @Ui(label = "Service time",
				unit = "ms", group = Descriptions.PERFORMANCE_GROUP, assumption = true,
				headline = true) Distribution serviceTimeMs,

		@Default("[]") @Description(Descriptions.SERVICE_TIME_BY_LOAD) @Ui(group = Descriptions.PERFORMANCE_GROUP,
				kind = "loadCurve") @OmitWhenDefault List<LoadPoint> serviceTimeByLoad,
		@Default("true") @Description(Descriptions.BLOCKING_DOWNSTREAM) @Ui(label = "Workers block on downstream calls",
				group = Descriptions.PERFORMANCE_GROUP) boolean blockingDownstream,
		@Description(Descriptions.CPU_CORES) @Ui(label = "CPU", unit = "vCPU", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Default("1.0") @Description(Descriptions.CPU_SHARE) @Ui(label = "CPU share of service time",
				group = Descriptions.RESOURCES_GROUP, step = 0.05) @Ge(0) @Le(1) double cpuShare,
		@Description(Resources.SIBLING_SPEED) @Ui(label = "Hyperthread sibling speed",
				group = Descriptions.RESOURCES_GROUP,
				step = 0.05) @OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Description(Descriptions.MEMORY_GIB) @Ui(label = "Memory", unit = "GiB", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Description(Descriptions.INSTANCE_TYPE) @Ui(
				group = Descriptions.RESOURCES_GROUP) @Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("2.0") @Description(Descriptions.MEMORY_PER_REQUEST_MB) @Ui(label = "Memory per request", unit = "MiB",
				group = Descriptions.RESOURCES_GROUP, assumption = true) @Gt(0) @Le(65_536) double memoryPerRequestMb,
		@Default("256.0") @Description(Descriptions.MEMORY_BASELINE_MB) @Ui(label = "Memory at idle", unit = "MiB",
				group = Descriptions.RESOURCES_GROUP, assumption = true) @Ge(0) @Le(1_048_576) double memoryBaselineMb,
		@Default("0.0") @Description(Descriptions.MEMORY_PER_RPS_MB) @Ui(label = "Memory per req/s", unit = "MiB",
				group = Descriptions.RESOURCES_GROUP, step = 0.01) @Ge(0) @Le(65_536) double memoryPerRpsMb,
		@Default("false") @Description(Descriptions.AUTOSCALING) @Ui(
				group = Descriptions.AUTOSCALING_GROUP) boolean autoscaling,
		@Default("1") @Description(Descriptions.MIN_REPLICAS) @Ui(group = Descriptions.AUTOSCALING_GROUP,
				visibleWhen = "autoscaling=true") @Ge(1) @Le(500) int minReplicas,
		@Default("10") @Description(Descriptions.MAX_REPLICAS) @Ui(group = Descriptions.AUTOSCALING_GROUP,
				visibleWhen = "autoscaling=true") @Ge(1) @Le(500) int maxReplicas,
		@Default("0.6") @Description(Descriptions.TARGET_UTILIZATION) @Ui(group = Descriptions.AUTOSCALING_GROUP,
				visibleWhen = "autoscaling=true", step = 0.05) @Ge(0.1) @Le(0.95) double targetUtilization,
		@Default("15.0") @Description(Descriptions.SCALE_INTERVAL_S) @Ui(label = "Evaluation interval", unit = "s",
				group = Descriptions.AUTOSCALING_GROUP,
				visibleWhen = "autoscaling=true") @Gt(0) @Le(600) double scaleIntervalS,
		@Default("20.0") @Description(Descriptions.STARTUP_S) @Ui(label = "Replica start-up time", unit = "s",
				group = Descriptions.AUTOSCALING_GROUP, assumption = true,
				visibleWhen = "autoscaling=true") @Ge(0) @Le(600) double startupS,
		@Default("1000") @Description(Descriptions.CONCURRENCY_LIMIT) @Ui(group = Descriptions.SERVERLESS_GROUP,
				visibleWhen = "platform=serverless") @Ge(1) @Le(100_000) int concurrencyLimit,
		@Description(Descriptions.COLD_START_MS) @Ui(label = "Cold start", unit = "ms",
				group = Descriptions.SERVERLESS_GROUP, assumption = true,
				visibleWhen = "platform=serverless") @Nullable Distribution coldStartMs,
		@Description(Descriptions.CLUSTER) @Ui(group = Descriptions.TECHNOLOGY_GROUP,
				visibleWhen = "platform=kubernetes", kind = "nodeRef",
				refType = "kubernetes_cluster") @Nullable String cluster,
		@Default("1000") @Description(Descriptions.SCALE_OUT_RATE) @Ui(label = "Scale-out rate per 10 s",
				group = Descriptions.SERVERLESS_GROUP,
				visibleWhen = "platform=serverless") @Ge(1) @Le(100_000) int scaleOutRate,
		@Default("auto") @Description(Descriptions.RUNTIME_MODEL) @Ui(label = "Concurrency model",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) RuntimeModel runtimeModel,
		@Default("1") @Description(Descriptions.PROCESSES_PER_REPLICA) @Ui(label = "Event-loop processes per replica",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(1) @Le(256) int processesPerReplica,
		@Description(Descriptions.GC_PAUSE_MS) @Ui(label = "GC pause", unit = "ms",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) @Ge(0) @Le(10_000) @Nullable Double gcPauseMs,
		@Description(Descriptions.GC_EVERY_REQUESTS) @Ui(label = "Requests between GCs",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) @Ge(1) @Le(10_000_000) @Nullable Integer gcEveryRequests,
		@Description(Descriptions.WARMUP_FACTOR) @Ui(label = "JIT warm-up slowdown",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, assumption = true,
				step = 0.1) @Ge(1) @Le(20) @Nullable Double warmupFactor,
		@Description(Descriptions.WARMUP_S) @Ui(label = "JIT warm-up time", unit = "s",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) @Ge(0) @Le(3600) @Nullable Double warmupS,
		@Default("300.0") @Description(Descriptions.KEEP_WARM_S) @Ui(label = "Keep warm", unit = "s",
				group = Descriptions.SERVERLESS_GROUP,
				visibleWhen = "platform=serverless") @Ge(0) @Le(7200) double keepWarmS,
		@Default("0.1") @Description(Descriptions.COST_PER_HOUR) @Ui(label = "Cost per replica-hour", unit = "$/h",
				group = Descriptions.COST_GROUP, assumption = true) @Ge(0) @Le(10_000) double costPerHour) {

	/** The default handler time per request: 8 ms median, 25 ms p95. */
	static final Distribution DEFAULT_SERVICE_TIME = new LogNormal(8.0, 25.0);

	/** What each runtime does by default (technology.py's {@code SERVICE_PROFILES}). */
	static final TechnologyProfiles<ServiceTechnology> PROFILES = TechnologyProfiles.builder(ServiceTechnology.class)
		.with(ServiceTechnology.NODEJS,
				runtime(TechnologyProfile.core(), "event_loop", new GcDefaults(2.0, 1500),
						new Startup(1.0, 0.0, 250, 700)))
		.with(ServiceTechnology.JAVA,
				runtime(TechnologyProfile.core(), "thread_pool", new GcDefaults(12.0, 2000),
						new Startup(2.5, 45.0, 1200, 3500)))
		.with(ServiceTechnology.DOTNET,
				runtime(TechnologyProfile.experimental(), "thread_pool", new GcDefaults(6.0, 2000),
						new Startup(1.5, 20.0, 600, 1500)))
		.with(ServiceTechnology.GO,
				runtime(TechnologyProfile.core(), "thread_pool", new GcDefaults(0.1, 1000),
						new Startup(1.0, 0.0, 100, 300)))
		.with(ServiceTechnology.RUST,
				runtime(TechnologyProfile.experimental(), "thread_pool", new GcDefaults(0.0, null),
						new Startup(1.0, 0.0, 60, 200)))
		.with(ServiceTechnology.PYTHON,
				runtime(TechnologyProfile.experimental(), "thread_pool", new GcDefaults(1.0, 2000),
						new Startup(1.0, 0.0, 250, 700)))
		.with(ServiceTechnology.PHP,
				runtime(TechnologyProfile.experimental(), "thread_pool", new GcDefaults(0.0, null),
						new Startup(1.0, 0.0, 300, 900)))
		.with(ServiceTechnology.RUBY,
				runtime(TechnologyProfile.experimental(), "thread_pool", new GcDefaults(3.0, 1500),
						new Startup(1.0, 0.0, 300, 900)))
		.with(ServiceTechnology.GENERIC,
				runtime(TechnologyProfile.core(), "thread_pool", new GcDefaults(0.0, null),
						new Startup(1.0, 0.0, 300, 900)))
		.build();

	/** The MiB in a GiB. */
	private static final double MIB_PER_GIB = 1024.0;

	/** The milliseconds in a second. */
	private static final double MS_PER_SECOND = 1000.0;

	/**
	 * Added before rounding down, so floating-point error does not cost a request slot.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	/** Taken off before rounding up, so floating-point error does not cost a replica. */
	private static final double WANTED_TOLERANCE = 1e-9;

	public ServiceConfig {
		serviceTimeByLoad = List.copyOf(serviceTimeByLoad);
		if (minReplicas > maxReplicas) {
			throw new IllegalArgumentException("min replicas must not exceed max replicas");
		}
	}

	/**
	 * The profile of a runtime.
	 * @param tier the profile to fill in
	 * @param model how the runtime's handlers share work
	 * @param gc the runtime's garbage collection
	 * @param startup how the runtime starts and warms up
	 * @return the filled-in profile
	 */
	private static TechnologyProfile runtime(TechnologyProfile tier, String model, GcDefaults gc, Startup startup) {
		TechnologyProfile profile = tier.with("runtimeModel", model).with("gcPauseMs", gc.pauseMs());
		Integer everyRequests = gc.everyRequests();
		if (everyRequests != null) {
			profile = profile.with("gcEveryRequests", everyRequests);
		}
		return profile.with("warmupFactor", startup.warmupFactor())
			.with("warmupS", startup.warmupS())
			.with("coldStartMs", new LogNormal(startup.coldStartMedianMs(), startup.coldStartP95Ms()));
	}

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * The extra time to start a serverless instance: as set, else its technology's (every
	 * runtime has one).
	 * @return the time's distribution, in milliseconds
	 */
	public Distribution coldStartInEffect() {
		return profile().distribution("coldStartMs", this.coldStartMs).orElseThrow();
	}

	/**
	 * The replicas the service starts with: as many as set, or for an autoscaling service
	 * as many within its bounds.
	 * @return the replicas
	 */
	public int initialReplicas() {
		if (!this.autoscaling) {
			return this.replicas;
		}
		return Math.min(Math.max(this.replicas, this.minReplicas), this.maxReplicas);
	}

	/**
	 * The replicas autoscaling wants for some work: enough to run it at the target
	 * utilization, within the bounds, as a Kubernetes HPA computes them.
	 * @param busyReplicas the replicas the work keeps busy (utilization × replicas)
	 * @return the replicas
	 */
	public int wantedReplicas(double busyReplicas) {
		int wanted = (busyReplicas > 0) ? (int) Math.ceil(busyReplicas / this.targetUtilization - WANTED_TOLERANCE) : 0;
		return Math.min(Math.max(wanted, this.minReplicas), this.maxReplicas);
	}

	/**
	 * Whether the service runs on Kubernetes (its pods scheduled on a cluster's nodes).
	 * @return {@code true} on the Kubernetes platform
	 */
	public boolean onKubernetes() {
		return this.platform == Platform.KUBERNETES;
	}

	/**
	 * The cluster it names, when it names one (an empty name is none).
	 * @return the cluster's id; {@code null} when it names none
	 */
	public @Nullable String namedCluster() {
		return (this.cluster == null || this.cluster.isEmpty()) ? null : this.cluster;
	}

	/**
	 * The cluster the service runs on, among the design's: the one it names, else the
	 * design's only one; none when it is not on Kubernetes or none fits.
	 * @param clusterIds the ids of the design's clusters
	 * @return the cluster's id; {@code null} when it runs on none
	 */
	public @Nullable String clusterIn(List<String> clusterIds) {
		if (!onKubernetes() || clusterIds.isEmpty()) {
			return null;
		}
		String named = namedCluster();
		if (named != null) {
			return clusterIds.contains(named) ? named : null;
		}
		return (clusterIds.size() == 1) ? clusterIds.getFirst() : null;
	}

	/**
	 * Whether the service's handlers are event loops: its own concurrency model, else its
	 * technology's, else a thread pool.
	 * @return {@code true} for event loops
	 */
	public boolean runsEventLoop() {
		return profile().choice("runtimeModel", this.runtimeModel, RuntimeModel.AUTO,
				RuntimeModel.THREAD_POOL) == RuntimeModel.EVENT_LOOP;
	}

	/**
	 * The requests one replica handles at once: its event-loop processes, or its workers.
	 * @return the handlers per replica
	 */
	public int handlersPerReplica() {
		return runsEventLoop() ? this.processesPerReplica : this.workersPerReplica;
	}

	/**
	 * Whether a handler stays busy while the service waits on its own calls: a
	 * thread-pool worker that blocks on them does; an event loop never does.
	 * @return {@code true} when a handler is held through the calls
	 */
	public boolean holdsHandlerThroughCalls() {
		return !runsEventLoop() && this.blockingDownstream;
	}

	/**
	 * Requests one replica can hold within its memory; empty when memory is not set.
	 * @return the requests one replica holds, zero or more
	 */
	public OptionalInt memorySlots() {
		if (this.memoryGib == null) {
			return OptionalInt.empty();
		}
		double free = this.memoryGib * MIB_PER_GIB - this.memoryBaselineMb;
		return OptionalInt.of((int) Math.max(0, Math.floor(free / this.memoryPerRequestMb + ROUNDING_TOLERANCE)));
	}

	/**
	 * The stop-the-world garbage collections in effect: the service's own settings, else
	 * its technology's.
	 * @return the median pause and the requests between collections; empty when either is
	 * zero or not set
	 */
	public Optional<GcPauses> gcPauses() {
		TechnologyProfile profile = profile();
		double pause = profile.number("gcPauseMs", this.gcPauseMs).orElse(0.0);
		int every = profile.wholeNumber("gcEveryRequests", this.gcEveryRequests).orElse(0);
		return (pause > 0 && every > 0) ? Optional.of(new GcPauses(pause, every)) : Optional.empty();
	}

	/**
	 * The JIT warm-up in effect: the service's own settings, else its technology's.
	 * @return the slowdown and its duration; empty when the slowdown is not above 1 or
	 * takes no time
	 */
	public Optional<Warmup> warmup() {
		TechnologyProfile profile = profile();
		double factor = profile.number("warmupFactor", this.warmupFactor).orElse(1.0);
		double seconds = profile.number("warmupS", this.warmupS).orElse(0.0);
		return (factor > 1.0 && seconds > 0) ? Optional.of(new Warmup(factor, seconds * MS_PER_SECOND))
				: Optional.empty();
	}

	/**
	 * The share of the service time that needs a core: the CPU share when the CPU is set,
	 * else all of it (without a CPU size nothing waits for a core).
	 * @return a share between 0 and 1
	 */
	public double cpuFraction() {
		return (this.cpuCores != null) ? this.cpuShare : 1.0;
	}

	/**
	 * A runtime's default garbage collection, as its profile records it: a pause of zero,
	 * or no count, where it pauses for none ({@link GcPauses} is what is in effect).
	 *
	 * @param pauseMs the median stop-the-world pause
	 * @param everyRequests requests between collections; {@code null}: not modelled
	 */
	private record GcDefaults(double pauseMs, @Nullable Integer everyRequests) {
	}

	/**
	 * How a runtime starts: its JIT warm-up, and a serverless instance's cold start.
	 *
	 * @param warmupFactor how much slower a new instance runs before it warms up
	 * @param warmupS the time a new instance takes to reach full speed
	 * @param coldStartMedianMs the median extra time to start a serverless instance
	 * @param coldStartP95Ms the 95th percentile of that time
	 */
	private record Startup(double warmupFactor, double warmupS, double coldStartMedianMs, double coldStartP95Ms) {
	}

}
