package io.archsim.model.component.queue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a queue's settings mean once its technology's defaults are filled in. Each case is
 * what the Python engine 0.16.0 gives.
 */
class MessageQueueConfigTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void consumerSlots_aPartitionedQueue_capsEachConsumerAtThePartitionsItOwns() {
		assertThat(
				config("{\"technology\": \"kafka\", \"consumers\": 3, \"partitions\": 8, \"consumerConcurrency\": 4}")
					.consumerSlots())
			.isEqualTo(8);
	}

	@Test
	void consumerSlots_anUnpartitionedQueue_isConsumersTimesTheirConcurrency() {
		assertThat(config(
				"{\"technology\": \"rabbitmq\", \"consumers\": 3, \"partitions\": 8, \"consumerConcurrency\": 4}")
			.consumerSlots()).isEqualTo(12);
	}

	@Test
	void consumerSlots_moreConsumersThanPartitions_countsOnlyThoseWithAPartition() {
		assertThat(config("{\"technology\": \"kafka\", \"consumers\": 3, \"partitions\": 2}").consumerSlots())
			.isEqualTo(2);
	}

	@Test
	void lingerMsInEffect_kafka_isItsDefault() {
		assertThat(config("{\"technology\": \"kafka\"}").lingerMsInEffect()).isEqualTo(5.0);
	}

	@Test
	void lingerMsInEffect_setOnThePart_isWhatIsSet() {
		assertThat(config("{\"technology\": \"kafka\", \"lingerMs\": 2}").lingerMsInEffect()).isEqualTo(2.0);
	}

	@Test
	void lingerMsInEffect_aGenericQueue_isZero() {
		assertThat(config("{}").lingerMsInEffect()).isZero();
	}

	@Test
	void acknowledgedByAll_kafka_waitsForAllReplicas() {
		assertThat(config("{\"technology\": \"kafka\"}").acknowledgedByAll()).isTrue();
	}

	@Test
	void acknowledgedByAll_setToTheLeader_doesNot() {
		assertThat(config("{\"technology\": \"kafka\", \"acks\": \"leader\"}").acknowledgedByAll()).isFalse();
	}

	private static MessageQueueConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), MessageQueueConfig.class);
	}

}
