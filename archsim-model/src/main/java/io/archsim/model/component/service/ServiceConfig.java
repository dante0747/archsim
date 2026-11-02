package io.archsim.model.component.service;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
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
public record ServiceConfig(@Default("generic") ServiceTechnology technology, @Default("vm") Platform platform,
		@Default("2") @Ge(1) @Le(500) int replicas, @Default("4") @Ge(1) @Le(4096) int workersPerReplica,
		@Default("50") @Ge(0) @Le(100_000) int queueCapacity,
		@Default("1000.0") @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,

		@DefaultFrom("DEFAULT_SERVICE_TIME") Distribution serviceTimeMs,

		@Default("[]") @OmitWhenDefault List<LoadPoint> serviceTimeByLoad, @Default("true") boolean blockingDownstream,
		@Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores, @Default("1.0") @Ge(0) @Le(1) double cpuShare,
		@OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("2.0") @Gt(0) @Le(65_536) double memoryPerRequestMb,
		@Default("256.0") @Ge(0) @Le(1_048_576) double memoryBaselineMb,
		@Default("0.0") @Ge(0) @Le(65_536) double memoryPerRpsMb, @Default("false") boolean autoscaling,
		@Default("1") @Ge(1) @Le(500) int minReplicas, @Default("10") @Ge(1) @Le(500) int maxReplicas,
		@Default("0.6") @Ge(0.1) @Le(0.95) double targetUtilization,
		@Default("15.0") @Gt(0) @Le(600) double scaleIntervalS, @Default("20.0") @Ge(0) @Le(600) double startupS,
		@Default("1000") @Ge(1) @Le(100_000) int concurrencyLimit, @Nullable Distribution coldStartMs,
		@Nullable String cluster, @Default("1000") @Ge(1) @Le(100_000) int scaleOutRate,
		@Default("auto") RuntimeModel runtimeModel, @Default("1") @Ge(1) @Le(256) int processesPerReplica,
		@Ge(0) @Le(10_000) @Nullable Double gcPauseMs, @Ge(1) @Le(10_000_000) @Nullable Integer gcEveryRequests,
		@Ge(1) @Le(20) @Nullable Double warmupFactor, @Ge(0) @Le(3600) @Nullable Double warmupS,
		@Default("300.0") @Ge(0) @Le(7200) double keepWarmS, @Default("0.1") @Ge(0) @Le(10_000) double costPerHour) {

	/** The default handler time per request: 8 ms median, 25 ms p95. */
	static final Distribution DEFAULT_SERVICE_TIME = new LogNormal(8.0, 25.0);

	/** What each runtime does by default (technology.py's {@code SERVICE_PROFILES}). */
	static final TechnologyProfiles<ServiceTechnology> PROFILES = new TechnologyProfiles<>(ServiceTechnology.class, Map
		.of(ServiceTechnology.NODEJS,
				runtime(TechnologyProfile.core(), "event_loop", new Gc(2.0, 1500), new Startup(1.0, 0.0, 250, 700)),
				ServiceTechnology.JAVA,
				runtime(TechnologyProfile.core(), "thread_pool", new Gc(12.0, 2000),
						new Startup(2.5, 45.0, 1200, 3500)),
				ServiceTechnology.DOTNET,
				runtime(TechnologyProfile.experimental(), "thread_pool", new Gc(6.0, 2000),
						new Startup(1.5, 20.0, 600, 1500)),
				ServiceTechnology.GO,
				runtime(TechnologyProfile.core(), "thread_pool", new Gc(0.1, 1000), new Startup(1.0, 0.0, 100, 300)),
				ServiceTechnology.RUST,
				runtime(TechnologyProfile.experimental(), "thread_pool", new Gc(0.0, null),
						new Startup(1.0, 0.0, 60, 200)),
				ServiceTechnology.PYTHON,
				runtime(TechnologyProfile.experimental(), "thread_pool", new Gc(1.0, 2000),
						new Startup(1.0, 0.0, 250, 700)),
				ServiceTechnology.PHP,
				runtime(TechnologyProfile.experimental(), "thread_pool", new Gc(0.0, null),
						new Startup(1.0, 0.0, 300, 900)),
				ServiceTechnology.RUBY,
				runtime(TechnologyProfile.experimental(), "thread_pool", new Gc(3.0, 1500),
						new Startup(1.0, 0.0, 300, 900)),
				ServiceTechnology.GENERIC,
				runtime(TechnologyProfile.core(), "thread_pool", new Gc(0.0, null), new Startup(1.0, 0.0, 300, 900))));

	/** The MiB in a GiB. */
	private static final double MIB_PER_GIB = 1024.0;

	/**
	 * Added before rounding down, so floating-point error does not cost a request slot.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

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
	private static TechnologyProfile runtime(TechnologyProfile tier, String model, Gc gc, Startup startup) {
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
	 * A runtime's garbage collection.
	 *
	 * @param pauseMs the median stop-the-world pause
	 * @param everyRequests requests between collections; {@code null}: not modelled
	 */
	private record Gc(double pauseMs, @Nullable Integer everyRequests) {
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
