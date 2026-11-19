package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.queue.QueueTechnology;
import io.archsim.model.document.Workload;

/**
 * The size of the parts that pass requests and messages on: a load balancer's node and a
 * message broker. A load balancer's throughput and memory per vCPU are illustrative, as
 * are a broker's but a Kafka broker's, which ArchSim's Kafka experiment measured
 * (ADR-0085).
 */
final class BrokerSizer {

	private static final double BYTES_PER_KIB = 1024;

	private static final double BYTES_PER_GIB = 1L << 30;

	/** The least auto memory of a load balancer node, in GiB. */
	private static final double MIN_BALANCER_MEMORY_GIB = 1;

	/** The least auto memory of a broker, in GiB. */
	private static final double MIN_BROKER_MEMORY_GIB = 2;

	private static final double MS_PER_SECOND = 1000;

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
		return new PartSize(cpu, memory, null, 0.0, why, null, null, need, null, false, null, null);
	}

	/**
	 * The size of one broker of a queue. A Kafka broker's CPU and memory are what
	 * ArchSim's Kafka experiment measured (ADR-0085); another broker's come from its
	 * illustrative throughput per vCPU. A log (Kafka, Redis Streams) keeps messages for
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
		BrokerLoad load = BrokerLoad.of(config, totalRps / brokers, onVm);
		double needCpu = load.busyCores() / Sizing.TARGET_CPU;
		double cpu = Math.min(Sizing.MAX_CPU, Steps.up(Math.max(1.0, needCpu), Sizing.CPU_STEPS));
		Stored stored = Stored.of(config, totalRps, workload, brokers);
		Map<Spec, String> why = Map.of(Spec.CPU_CORES, load.cpuWhy(), Spec.MEMORY_GIB, load.memoryWhy(),
				Spec.STORAGE_GIB, stored.why());
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, load.busyCores(), Need.CPU_CORES, needCpu, Need.MEMORY_GIB,
				load.memoryGib(), Need.STORAGE_GIB, stored.gib());
		double memory = Steps.memory(Math.max(Sizing.BROKER_GIB_PER_VCPU * cpu, load.memoryGib()),
				MIN_BROKER_MEMORY_GIB);
		return new PartSize(cpu, memory, Steps.disk(stored.gib() / Sizing.STORAGE_FILL), 0.0, why, null, null, need,
				null, false, null, null);
	}

	/**
	 * What one broker needs: its cores busy at the peak and its memory, with how each was
	 * worked out.
	 *
	 * @param busyCores its cores busy at the peak
	 * @param memoryGib its memory, the operating system's included on a VM of its own
	 * @param cpuWhy how its CPU was worked out
	 * @param memoryWhy how its memory was worked out
	 */
	private record BrokerLoad(double busyCores, double memoryGib, String cpuWhy, String memoryWhy) {

		static BrokerLoad of(MessageQueueConfig config, double perBroker, boolean onVm) {
			double os = onVm ? Sizing.OS_MEMORY_GIB : 0.0;
			if (config.technology() == QueueTechnology.KAFKA) {
				return kafka(perBroker, os);
			}
			double needCpu = perBroker / Sizing.BROKER_MSGS_PER_VCPU;
			return new BrokerLoad(needCpu * Sizing.TARGET_CPU, Sizing.BROKER_GIB_PER_VCPU * needCpu + os,
					"%s messages/s per broker at peak at about %s per vCPU (illustrative)"
						.formatted(SizingText.brief(perBroker), SizingText.brief(Sizing.BROKER_MSGS_PER_VCPU)),
					"%d GiB per vCPU for buffers and page cache (illustrative)".formatted(Sizing.BROKER_GIB_PER_VCPU));
		}

		// what ArchSim's Kafka experiment measured of its broker
		private static BrokerLoad kafka(double perBroker, double os) {
			double busy = Sizing.KAFKA_FIXED_CORES + perBroker * Sizing.KAFKA_CPU_MS_PER_MESSAGE / MS_PER_SECOND;
			String cpu = ("%s messages/s per broker at peak: %s fixed cores and %s ms of CPU a message (measured, "
					+ "ArchSim's Kafka experiment) = %s cores busy")
				.formatted(SizingText.brief(perBroker), Sizing.KAFKA_FIXED_CORES, Sizing.KAFKA_CPU_MS_PER_MESSAGE,
						SizingText.brief(busy));
			String memory = ("a %s working set (measured, ArchSim's Kafka experiment); the spec keeps at least %d GiB "
					+ "per vCPU for page cache (illustrative)")
				.formatted(SizingText.gib(Sizing.KAFKA_WORKING_SET_GIB), Sizing.BROKER_GIB_PER_VCPU);
			return new BrokerLoad(busy, Sizing.KAFKA_WORKING_SET_GIB + os, cpu, memory);
		}

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
