package io.archsim.engine.components.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.ServiceConfig;

/**
 * What a service adds to its part of a run's report: its CPU cores' and memory's figures
 * and ceilings, its pauses, the series behind them, and the behaviours its technology
 * brings that are in effect.
 */
final class ServiceReport {

	private static final double MS_PER_SECOND = 1000.0;

	private static final double MIB_PER_GIB = 1024.0;

	private static final int MEMORY_DIGITS = 3;

	private static final double HALF_BUCKET = 0.5;

	private ServiceReport() {
	}

	/**
	 * The service's own summary figures: its cores' utilization, its memory's peak and
	 * its pauses, each when it is modelled.
	 * @param service the service
	 * @return the figures, in the report's order
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(ServiceState service) {
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		ServiceConfig config = service.config();
		Gauge busy = service.coreBusy();
		if (busy != null) {
			double executors = service.replicas().getFirst().cores().executors() * service.replicasMean();
			figures.put("cpuCoreUtilization", Cores.utilization(busy, executors).orElse(null));
		}
		Double memoryGib = config.memoryGib();
		if (service.memorySlots().isPresent() && memoryGib != null) {
			double peakMb = config.memoryBaselineMb() + service.peakLoad() * config.memoryPerRequestMb();
			if (config.memoryPerRpsMb() > 0) {
				peakMb += config.memoryPerRpsMb() * peakRpsPerReplica(service);
			}
			figures.put("memoryPeakGiB", PythonRound.round(Math.min(peakMb / MIB_PER_GIB, memoryGib), MEMORY_DIGITS));
		}
		if (service.gc() != null) {
			Counter pauses = service.metrics().counters().get(GarbageCollector.PAUSES);
			figures.put(GarbageCollector.PAUSES, (pauses != null) ? pauses.windowCount() : 0L);
		}
		return figures;
	}

	/**
	 * The service's own series: requests in flight, busy cores, pause time, warming
	 * replicas and an event loop's lag, each when it is modelled.
	 * @param service the service
	 * @return the series over the whole timeline, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(ServiceState service) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		NodeMetrics metrics = service.metrics();
		series.put("inflightMean", RuntimeComponent.series(metrics.inflight().meanSeries()));
		Gauge busy = service.coreBusy();
		if (busy != null) {
			series.put("cpuBusyCores", Cores.busyCores(busy, service.replicas().getFirst().cores().speed()));
		}
		double bucketS = metrics.timeline().bucketMs() / MS_PER_SECOND;
		if (service.gc() != null) {
			series.put("gcPauseMs", RuntimeComponent
				.series(Arrays.stream(service.pauseMs()).map((pausedMs) -> pausedMs / bucketS).toArray()));
		}
		if (service.warmup() != null) {
			series.put("warmingReplicas", RuntimeComponent.series(service.warming().meanSeries()));
		}
		if (service.config().runsEventLoop()) {
			series.put("eventLoopLagMs", metrics.queueWait().series());
		}
		return series;
	}

	/**
	 * The ceilings the service's cores and memory set: the rate at which a replica's
	 * cores fill at that rate's factor, slowed on a machine (on the design's host, the
	 * multiple of the load at which its threads fill with the other parts and the
	 * clients' traffic on it; on its own hyperthreads, every vCPU busy); and the rate at
	 * which its memory fills with requests.
	 * @param service the service
	 * @param holdMs the mean time a request held a handler, if any did
	 * @param serviceMs the mean service time, if any request was served
	 * @return the ceilings in requests per second, CPU first
	 */
	static SequencedMap<String, Double> resourceCapacity(ServiceState service, OptionalDouble holdMs,
			OptionalDouble serviceMs) {
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>();
		ServiceConfig config = service.config();
		double replicas = service.replicasMean();
		Double cores = config.cpuCores();
		if (cores != null && serviceMs.orElse(0.0) > 0 && config.cpuShare() > 0) {
			double perReplica = cores * MS_PER_SECOND
					/ (service.baseServiceMs(serviceMs.getAsDouble()) * config.cpuShare());
			// the replicas' cores fill at that rate's factor, at the speed of their
			// machine
			double fullSpeed = service.curve().capacityRps(perReplica) * replicas;
			double ceiling = service.machine()
				.capacity(fullSpeed, cores * replicas, service.metrics().arrivals().windowRate());
			ceilings.put("cpu", ceiling);
		}
		OptionalInt memorySlots = service.memorySlots();
		if (memorySlots.isPresent() && memorySlots.getAsInt() < config.workersPerReplica() && holdMs.orElse(0.0) > 0) {
			ceilings.put("memory", memorySlots.getAsInt() * replicas * MS_PER_SECOND / holdMs.getAsDouble());
		}
		return ceilings;
	}

	/**
	 * The rate at which a service's handlers fill when its service time follows its load:
	 * its own work at that rate's factor, the rest of the time a request holds a handler
	 * (its calls) at none.
	 * @param service the service
	 * @param slots its handlers
	 * @param holdMs the mean time a request held one
	 * @param serviceMs the mean service time, above zero
	 * @return requests per second
	 */
	static double slotCapacity(ServiceState service, double slots, double holdMs, double serviceMs) {
		double replicas = Math.max(1.0, service.replicasMean());
		double perReplica = service.curve()
			.rateFilling(service.baseServiceMs(serviceMs), slots / replicas * MS_PER_SECOND,
					Math.max(0.0, holdMs - serviceMs));
		return perReplica * replicas;
	}

	/**
	 * The behaviours the service's technology brings that are in effect: an event loop or
	 * a thread per request, its pauses and its warm-up when they are modelled.
	 * @param service the service
	 * @return the traits, in the technology's order
	 */
	static List<TechnologyTrait> behaviour(ServiceState service) {
		boolean eventLoop = service.config().runsEventLoop();
		boolean pauses = service.gc() != null;
		boolean warms = service.warmup() != null;
		Map<String, Boolean> active = Map.of("event_loop", eventLoop, "gc_pauses", pauses, "v8_gc", pauses,
				"jit_warmup", warms, "tiered_compilation", warms, "thread_per_request", !eventLoop);
		return service.traits().stream().filter((trait) -> active.getOrDefault(trait.id(), true)).toList();
	}

	/**
	 * The highest rate of requests one replica finished (completed or failed downstream)
	 * in a bucket of the measurement window, which the memory per request per second
	 * follows.
	 * @param service the service
	 * @return requests per second
	 */
	private static double peakRpsPerReplica(ServiceState service) {
		NodeMetrics metrics = service.metrics();
		Timeline timeline = metrics.timeline();
		int bucketMs = timeline.bucketMs();
		long[] completed = metrics.completed().buckets();
		long[] failed = metrics.failedDownstream().buckets();
		double replicas = service.replicasMean();
		double peak = 0.0;
		for (int bucket = 0; bucket < timeline.buckets(); bucket++) {
			double middle = (bucket + HALF_BUCKET) * bucketMs;
			if (timeline.windowStart() <= middle && middle < timeline.windowEnd() && replicas > 0) {
				peak = Math.max(peak, (completed[bucket] + failed[bucket]) * MS_PER_SECOND / bucketMs / replicas);
			}
		}
		return peak;
	}

}
