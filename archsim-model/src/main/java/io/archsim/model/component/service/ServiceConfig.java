package io.archsim.model.component.service;

import static io.archsim.model.component.Resources.INSTANCE_TYPE;
import static io.archsim.model.component.Resources.MAX_CPU_CORES;
import static io.archsim.model.component.Resources.MAX_MEMORY_GIB;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.LogNormal;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

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

		@Default("{\"kind\": \"lognormal\", \"median\": 8.0, \"p95\": 25.0}") Distribution serviceTimeMs,

		@Default("[]") @OmitWhenDefault List<LoadPoint> serviceTimeByLoad, @Default("true") boolean blockingDownstream,
		@Gt(0) @Le(MAX_CPU_CORES) @Nullable Double cpuCores, @Default("1.0") @Ge(0) @Le(1) double cpuShare,
		@OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Gt(0) @Le(MAX_MEMORY_GIB) @Nullable Double memoryGib, @Matches(INSTANCE_TYPE) @Nullable String instanceType,
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

	/** What each runtime does by default (technology.py's {@code SERVICE_PROFILES}). */
	static final TechnologyProfiles<ServiceTechnology> PROFILES = new TechnologyProfiles<>(ServiceTechnology.class, Map
		.of(ServiceTechnology.NODEJS, runtime(TechnologyProfile.core(), "event_loop", 2.0, 1500, 1.0, 0.0, 250, 700),
				ServiceTechnology.JAVA,
				runtime(TechnologyProfile.core(), "thread_pool", 12.0, 2000, 2.5, 45.0, 1200, 3500),
				ServiceTechnology.DOTNET,
				runtime(TechnologyProfile.experimental(), "thread_pool", 6.0, 2000, 1.5, 20.0, 600, 1500),
				ServiceTechnology.GO, runtime(TechnologyProfile.core(), "thread_pool", 0.1, 1000, 1.0, 0.0, 100, 300),
				ServiceTechnology.RUST,
				runtime(TechnologyProfile.experimental(), "thread_pool", 0.0, null, 1.0, 0.0, 60, 200),
				ServiceTechnology.PYTHON,
				runtime(TechnologyProfile.experimental(), "thread_pool", 1.0, 2000, 1.0, 0.0, 250, 700),
				ServiceTechnology.PHP,
				runtime(TechnologyProfile.experimental(), "thread_pool", 0.0, null, 1.0, 0.0, 300, 900),
				ServiceTechnology.RUBY,
				runtime(TechnologyProfile.experimental(), "thread_pool", 3.0, 1500, 1.0, 0.0, 300, 900),
				ServiceTechnology.GENERIC,
				runtime(TechnologyProfile.core(), "thread_pool", 0.0, null, 1.0, 0.0, 300, 900)));

	public ServiceConfig {
		serviceTimeByLoad = List.copyOf(serviceTimeByLoad);
		if (minReplicas > maxReplicas) {
			throw new IllegalArgumentException("min replicas must not exceed max replicas");
		}
	}

	private static TechnologyProfile runtime(TechnologyProfile tier, String model, double gcPauseMs,
			@Nullable Integer gcEveryRequests, double warmupFactor, double warmupS, double coldStartMedian,
			double coldStartP95) {
		TechnologyProfile profile = tier.with("runtimeModel", model).with("gcPauseMs", gcPauseMs);
		if (gcEveryRequests != null) {
			profile = profile.with("gcEveryRequests", gcEveryRequests);
		}
		return profile.with("warmupFactor", warmupFactor)
			.with("warmupS", warmupS)
			.with("coldStartMs", new LogNormal(coldStartMedian, coldStartP95));
	}

	public TechnologyProfile profile() {
		return PROFILES.of(technology);
	}

	/**
	 * Whether the service runs on Kubernetes (its pods scheduled on a cluster's nodes).
	 */
	public boolean onKubernetes() {
		return platform == Platform.KUBERNETES;
	}

	/** The cluster it names, when it names one (an empty name is none). */
	public @Nullable String namedCluster() {
		return cluster == null || cluster.isEmpty() ? null : cluster;
	}

	/**
	 * The cluster the service runs on, among the design's: the one it names, else the
	 * design's only one; none when it is not on Kubernetes or none fits.
	 */
	public @Nullable String clusterIn(List<String> clusterIds) {
		if (!onKubernetes() || clusterIds.isEmpty()) {
			return null;
		}
		String named = namedCluster();
		if (named != null) {
			return clusterIds.contains(named) ? named : null;
		}
		return clusterIds.size() == 1 ? clusterIds.getFirst() : null;
	}

	/** Requests one replica can hold within its memory; empty when memory is not set. */
	public OptionalInt memorySlots() {
		if (memoryGib == null) {
			return OptionalInt.empty();
		}
		double free = memoryGib * 1024.0 - memoryBaselineMb;
		return OptionalInt.of((int) Math.max(0, Math.floor(free / memoryPerRequestMb + 1e-9)));
	}
}
