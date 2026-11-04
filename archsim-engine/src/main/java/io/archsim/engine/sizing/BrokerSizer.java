package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.document.Workload;

/**
 * The size of the parts that pass requests and messages on: a load balancer's node and a
 * message broker. Their throughput per vCPU and memory per vCPU are illustrative.
 */
final class BrokerSizer {

	private static final double BYTES_PER_KIB = 1024;

	private static final double BYTES_PER_GIB = 1L << 30;

	/** The least auto memory of a load balancer node, in GiB. */
	private static final double MIN_BALANCER_MEMORY_GIB = 1;

	/** The least auto memory of a broker, in GiB. */
	private static final double MIN_BROKER_MEMORY_GIB = 2;

	/** A log keeps this many copies of each message, at most one per broker. */
	private static final int LOG_COPIES = 3;

	private BrokerSizer() {
	}

	/**
	 * The size of one load balancer node.
	 * @param totalRps the requests per second it passes on at the peak
	 * @return its size
	 */
	static PartSize balancer(double totalRps) {
		double needCpu = totalRps / Sizing.PROXY_RPS_PER_VCPU;
		double cpu = Math.min(Sizing.MAX_CPU, Steps.up(Math.max(1.0, needCpu), Sizing.CPU_STEPS));
		double needMemory = Sizing.PROXY_GIB_PER_VCPU * needCpu + Sizing.OS_MEMORY_GIB;
		Map<Spec, String> why = Map.of(Spec.CPU_CORES,
				"%s req/s at peak at about %s req/s per vCPU (illustrative)".formatted(SizingText.brief(totalRps),
						SizingText.brief(Sizing.PROXY_RPS_PER_VCPU)),
				Spec.MEMORY_GIB,
				"%d GiB per vCPU: a proxy keeps little state (illustrative)".formatted(Sizing.PROXY_GIB_PER_VCPU));
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, needCpu * Sizing.TARGET_CPU, Need.CPU_CORES, needCpu,
				Need.MEMORY_GIB, needMemory);
		double memory = Steps.memory(Math.max(Sizing.PROXY_GIB_PER_VCPU * cpu, needMemory), MIN_BALANCER_MEMORY_GIB);
		return new PartSize(cpu, memory, null, 0.0, why, null, null, need, null, false);
	}

	/**
	 * The size of one broker of a queue. A log (Kafka, Redis Streams) keeps messages for
	 * its retention at the usual rate; other brokers delete on ack and need room for a
	 * backlog, an hour of peak publishes.
	 * @param config the queue's settings
	 * @param totalRps the messages per second all its brokers take at the peak
	 * @param workload the design's workload, for its usual rate
	 * @param onVm whether a broker runs on a VM of its own
	 * @return its size
	 */
	static PartSize queue(MessageQueueConfig config, double totalRps, Workload workload, boolean onVm) {
		int brokers = Math.max(1, config.brokers());
		double needCpu = totalRps / brokers / Sizing.BROKER_MSGS_PER_VCPU;
		double cpu = Math.min(Sizing.MAX_CPU, Steps.up(Math.max(1.0, needCpu), Sizing.CPU_STEPS));
		Stored stored = Stored.of(config, totalRps, workload, brokers);
		double needMemory = Sizing.BROKER_GIB_PER_VCPU * needCpu + (onVm ? Sizing.OS_MEMORY_GIB : 0.0);
		Map<Spec, String> why = Map.of(Spec.CPU_CORES,
				"%s messages/s per broker at peak at about %s per vCPU (illustrative)"
					.formatted(SizingText.brief(totalRps / brokers), SizingText.brief(Sizing.BROKER_MSGS_PER_VCPU)),
				Spec.MEMORY_GIB,
				"%d GiB per vCPU for buffers and page cache (illustrative)".formatted(Sizing.BROKER_GIB_PER_VCPU),
				Spec.STORAGE_GIB, stored.why());
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, needCpu * Sizing.TARGET_CPU, Need.CPU_CORES, needCpu,
				Need.MEMORY_GIB, needMemory, Need.STORAGE_GIB, stored.gib());
		double memory = Steps.memory(Math.max(Sizing.BROKER_GIB_PER_VCPU * cpu, needMemory), MIN_BROKER_MEMORY_GIB);
		return new PartSize(cpu, memory, Steps.disk(stored.gib() / Sizing.STORAGE_FILL), 0.0, why, null, null, need,
				null, false);
	}

	/**
	 * The messages one broker keeps.
	 *
	 * @param gib how much, in GiB
	 * @param why how it was worked out
	 */
	private record Stored(double gib, String why) {

		static Stored of(MessageQueueConfig config, double totalRps, Workload workload, int brokers) {
			boolean log = Sizing.LOG_BROKERS.contains(config.technology().json());
			double peak = workload.peakRate();
			double usual = (peak > 0) ? Math.min(1.0, workload.rps() / peak) : 1.0;
			double rate = log ? totalRps * usual : totalRps;
			double seconds = log ? Sizing.LOG_RETENTION_S : Sizing.BACKLOG_S;
			int copies = log ? Math.min(LOG_COPIES, brokers) : 1;
			double kib = config.messageSizeKb();
			double gib = rate * kib * BYTES_PER_KIB * seconds * copies / brokers / BYTES_PER_GIB;
			String over = (brokers > 1) ? " over " + brokers + " brokers" : "";
			if (log) {
				String kept = (copies > 1) ? " × " + copies + " copies" : "";
				return new Stored(gib,
						"%s messages/s × %s KiB kept 7 days (Kafka's default retention)%s%s = %s, %s".formatted(
								SizingText.brief(rate), SizingText.brief(kib), kept, over, SizingText.gib(gib),
								SizingText.fill()));
			}
			return new Stored(gib,
					"An hour of peak publishes as a backlog buffer (%s messages/s × %s KiB%s) = %s, %s".formatted(
							SizingText.brief(totalRps), SizingText.brief(kib), over, SizingText.gib(gib),
							SizingText.fill()));
		}

	}

}
