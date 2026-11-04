package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.engine.settings.LoadCurve;
import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Steps.Explained;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceConfig;

/**
 * The size of a service replica: CPU for its work at the peak, capped at what its
 * handlers can use; memory for every request it can hold, working and queued, so it never
 * refuses for memory before its queue is full, capped at a large replica. A serverless
 * function is sized by {@link FunctionSizer}.
 */
final class ServiceSizer {

	private static final double MIB_PER_GIB = 1024;

	private static final double MILLIS_PER_SECOND = 1000;

	/** The least auto memory of a replica, in GiB. */
	private static final double MIN_MEMORY_GIB = 0.5;

	/** A request that holds no memory still divides as if it held a little. */
	private static final double SMALLEST_REQUEST_MB = 1e-9;

	private ServiceSizer() {
	}

	/**
	 * The size of one replica.
	 * @param config the service's settings
	 * @param totalRps the requests per second all its replicas carry at the peak
	 * @param placement where it runs
	 * @return its size
	 */
	static PartSize size(ServiceConfig config, double totalRps, Placement placement) {
		if (config.platform() == Platform.SERVERLESS) {
			return FunctionSizer.size(config);
		}
		double replicas = replicas(config);
		double perReplica = totalRps / replicas;
		Work work = placement.atSpeed(busy(config, perReplica), intro(config, perReplica, totalRps, replicas));
		Explained cpu = Steps.capped(Steps.cpu(work.busy(), work.intro()), config.handlersPerReplica(),
				handlersReason(config));
		ReplicaMemory memory = ReplicaMemory.of(config, perReplica, placement.onVm());
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, work.busy(), Need.CPU_CORES, work.busy() / Sizing.TARGET_CPU,
				Need.MEMORY_GIB_MAX, memory.mostGib());
		return new PartSize(cpu.value(), memory.gib(), null, 0.0,
				Map.of(Spec.CPU_CORES, cpu.why(), Spec.MEMORY_GIB, memory.why()), null, null, need,
				memory.model(replicas), false);
	}

	/**
	 * The replicas the load is spread over: all of them at full scale when it autoscales.
	 * @param config the service's settings
	 * @return the replicas
	 */
	private static double replicas(ServiceConfig config) {
		if (config.autoscaling()) {
			return Math.max(1.0, Math.max(config.maxReplicas(), config.replicas()));
		}
		return Math.max(1.0, config.replicas());
	}

	/**
	 * The CPU cores one replica keeps busy at full speed.
	 * @param config the service's settings
	 * @param perReplica the requests per second one replica carries
	 * @return the busy cores
	 */
	private static double busy(ServiceConfig config, double perReplica) {
		double factor = LoadCurve.of(config.serviceTimeByLoad()).factorAt(perReplica);
		return perReplica * config.serviceTimeMs().expected() * factor * config.cpuShare() / MILLIS_PER_SECOND;
	}

	private static String intro(ServiceConfig config, double perReplica, double totalRps, double replicas) {
		String scale = config.autoscaling() ? " at full scale" : "";
		return "%s req/s per replica at peak (%s over %s%s) × %s".formatted(SizingText.brief(perReplica),
				SizingText.brief(totalRps), SizingText.plain(replicas), scale, cpuPerRequest(config, perReplica));
	}

	private static String cpuPerRequest(ServiceConfig config, double perReplica) {
		String ms = SizingText.brief(config.serviceTimeMs().expected());
		String each = (config.cpuShare() == 1.0) ? ms + " ms of CPU each"
				: ms + " ms × " + SizingText.percent(config.cpuShare()) + " CPU share";
		LoadCurve curve = LoadCurve.of(config.serviceTimeByLoad());
		if (curve.isEmpty()) {
			return each;
		}
		return each + " × " + SizingText.brief(curve.factorAt(perReplica)) + " at that rate (service time by load)";
	}

	private static String handlersReason(ServiceConfig config) {
		int handlers = config.handlersPerReplica();
		String handler = config.runsEventLoop() ? "event loop" : "worker";
		return "%d %s%s cannot use more cores (add workers or replicas)".formatted(handlers, handler,
				SizingText.plural(handlers));
	}

	/**
	 * What one replica holds.
	 *
	 * @param working the requests it works on at once
	 * @param held the requests it holds, working and queued
	 * @param mb the memory that takes, in MiB
	 * @param os the memory its operating system takes, in GiB
	 */
	private record Holding(int working, double held, double mb, double os) {
	}

	/**
	 * A replica's memory: its baseline, every request it can hold (an event loop holds
	 * many in flight, one per worker process), and what follows its throughput.
	 *
	 * @param gib the memory it gets
	 * @param mostGib the most it holds: every request, and the one arriving when it is
	 * full
	 * @param why how it was worked out
	 * @param fixedGib its memory at idle, with the operating system
	 * @param perRequestGib its memory per request held
	 * @param perRpsGib its memory per request per second
	 */
	private record ReplicaMemory(double gib, double mostGib, String why, double fixedGib, double perRequestGib,
			double perRpsGib) {

		static ReplicaMemory of(ServiceConfig config, double perReplica, boolean onVm) {
			int working = config.runsEventLoop() ? config.workersPerReplica() * config.processesPerReplica()
					: config.handlersPerReplica();
			double held = working + (double) config.queueCapacity();
			double flowing = config.memoryPerRpsMb() * perReplica;
			double mb = config.memoryBaselineMb() + held * config.memoryPerRequestMb() + flowing;
			double os = onVm ? Sizing.OS_MEMORY_GIB : 0.0;
			double wanted = Steps.memory((mb / MIB_PER_GIB + os) * Sizing.MEMORY_HEADROOM, MIN_MEMORY_GIB);
			double gib = Math.min(wanted, Sizing.MAX_SERVICE_MEMORY_GIB);
			String why = explained(config, perReplica, new Holding(working, held, mb, os));
			if (gib < wanted) {
				double refuseAt = Math.floor((gib * MIB_PER_GIB - config.memoryBaselineMb())
						/ Math.max(config.memoryPerRequestMb(), SMALLEST_REQUEST_MB));
				why += "; capped at %d GiB, so past about %s requests held it refuses (out of memory)"
					.formatted(Sizing.MAX_SERVICE_MEMORY_GIB, SizingText.brief(refuseAt));
			}
			return new ReplicaMemory(gib, (mb + config.memoryPerRequestMb()) / MIB_PER_GIB + os, why,
					config.memoryBaselineMb() / MIB_PER_GIB + os, config.memoryPerRequestMb() / MIB_PER_GIB,
					config.memoryPerRpsMb() / MIB_PER_GIB);
		}

		private static String explained(ServiceConfig config, double perReplica, Holding holding) {
			String rate = (config.memoryPerRpsMb() * perReplica > 0) ? " + %s req/s × %s MiB per req/s"
				.formatted(SizingText.brief(perReplica), SizingText.brief(config.memoryPerRpsMb())) : "";
			String system = (holding.os() != 0) ? " + " + SizingText.gib(holding.os()) + " for the operating system"
					: "";
			return "%s MiB base + %s requests held (%d working, %s queued) × %s MiB%s = %s%s, %s".formatted(
					SizingText.brief(config.memoryBaselineMb()), SizingText.brief(holding.held()), holding.working(),
					SizingText.brief(config.queueCapacity()), SizingText.brief(config.memoryPerRequestMb()), rate,
					SizingText.gib(holding.mb() / MIB_PER_GIB), system, SizingText.headroom());
		}

		/**
		 * The memory model of the replica, for its typical memory.
		 * @param replicas the replicas the requests are spread over
		 * @return the model
		 */
		MemoryModel model(double replicas) {
			return new MemoryModel(this.fixedGib, this.perRequestGib, replicas, this.perRpsGib);
		}

	}

}
