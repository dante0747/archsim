package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A broker's size. A Kafka broker's comes from ArchSim's Kafka experiment (ADR-0085);
 * other brokers' from their illustrative throughput per vCPU.
 */
class BrokerSizerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double MS_PER_SECOND = 1000;

	private static final double TOLERANCE = 1e-9;

	/** 63 messages a second over 3 brokers. */
	private static final double PER_BROKER = 21;

	private static final Workload WORKLOAD = RecordCodec.read(JSON.readTree("{\"rps\": 63}"), Workload.class);

	@Test
	void queue_aKafkaBroker_isBusyForItsFixedCoresAndEachMessage() {
		PartSize size = BrokerSizer.queue(config("kafka"), 63, WORKLOAD, false);
		double busy = Sizing.KAFKA_FIXED_CORES + PER_BROKER * Sizing.KAFKA_CPU_MS_PER_MESSAGE / MS_PER_SECOND;

		assertThat(size.need().get(Need.BUSY_CORES)).isCloseTo(busy, within(TOLERANCE));
		assertThat(size.need().get(Need.CPU_CORES)).isCloseTo(busy / Sizing.TARGET_CPU, within(TOLERANCE));
		assertThat(size.why().get(Spec.CPU_CORES)).isEqualTo("21 messages/s per broker at peak: 0.39 fixed cores and "
				+ "0.053 ms of CPU a message (measured, ArchSim's Kafka experiment) = 0.39 cores busy");
	}

	@Test
	void queue_aKafkaBroker_needsItsMeasuredWorkingSet() {
		PartSize size = BrokerSizer.queue(config("kafka"), 63, WORKLOAD, false);

		assertThat(size.need().get(Need.MEMORY_GIB)).isEqualTo(Sizing.KAFKA_WORKING_SET_GIB);
		assertThat(size.why().get(Spec.MEMORY_GIB)).isEqualTo("a 973 MiB working set (measured, ArchSim's Kafka "
				+ "experiment); the spec keeps at least 2 GiB per vCPU for page cache (illustrative)");
	}

	@Test
	void queue_aKafkaBrokerOnAVmOfItsOwn_needsTheOperatingSystemToo() {
		PartSize size = BrokerSizer.queue(config("kafka"), 63, WORKLOAD, true);

		assertThat(size.need().get(Need.MEMORY_GIB)).isCloseTo(Sizing.KAFKA_WORKING_SET_GIB + Sizing.OS_MEMORY_GIB,
				within(TOLERANCE));
	}

	@Test
	void queue_anotherBroker_keepsItsIllustrativeThroughput() {
		PartSize size = BrokerSizer.queue(config("rabbitmq"), 63, WORKLOAD, false);

		assertThat(size.need().get(Need.CPU_CORES)).isCloseTo(PER_BROKER / Sizing.BROKER_MSGS_PER_VCPU,
				within(TOLERANCE));
	}

	private static MessageQueueConfig config(String technology) {
		return RecordCodec.read(JSON.readTree("{\"technology\": \"%s\", \"brokers\": 3}".formatted(technology)),
				MessageQueueConfig.class);
	}

}
