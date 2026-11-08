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

	@Test
	void partitioningInEffect_kafka_isByKey() {
		assertThat(config("{\"technology\": \"kafka\"}").partitioningInEffect()).isEqualTo(Partitioning.KEY);
	}

	@Test
	void partitioningInEffect_aTechnologyWithoutPartitions_isNone() {
		assertThat(config("{\"technology\": \"sqs\"}").partitioningInEffect()).isEqualTo(Partitioning.NONE);
	}

	@Test
	void deliversAtMostOnce_setOnThePart_isTrue() {
		assertThat(config("{\"technology\": \"nats\", \"delivery\": \"at_most_once\"}").deliversAtMostOnce()).isTrue();
	}

	@Test
	void deliversAtMostOnce_aTechnologyWithoutADefault_isFalse() {
		assertThat(config("{\"technology\": \"generic\"}").deliversAtMostOnce()).isFalse();
	}

	@Test
	void visibilityTimeoutSInEffect_sqs_isItsDefault() {
		assertThat(config("{\"technology\": \"sqs\"}").visibilityTimeoutSInEffect()).hasValue(30.0);
	}

	@Test
	void visibilityTimeoutSInEffect_kafka_isEmpty() {
		assertThat(config("{\"technology\": \"kafka\"}").visibilityTimeoutSInEffect()).isEmpty();
	}

	@Test
	void fetchesInCycles_aConcurrencyAboveOne_isTrue() {
		assertThat(config("{\"consumerConcurrency\": 2}").fetchesInCycles()).isTrue();
	}

	@Test
	void fetchesInCycles_aFetchOverhead_isTrue() {
		assertThat(config("{\"fetchOverheadMs\": {\"kind\": \"constant\", \"value\": 2}}").fetchesInCycles()).isTrue();
	}

	@Test
	void fetchesInCycles_neither_isFalse() {
		assertThat(config("{}").fetchesInCycles()).isFalse();
	}

	@Test
	void flowThreshold_rabbitMqWithMemory_isTheBacklogTheWatermarkHolds() {
		assertThat(config("{\"technology\": \"rabbitmq\", \"memoryGib\": 1, \"messageSizeKb\": 4}").flowThreshold())
			.hasValue(157_286.4);
	}

	@Test
	void flowThreshold_rabbitMqWithoutMemory_isEmpty() {
		assertThat(config("{\"technology\": \"rabbitmq\"}").flowThreshold()).isEmpty();
	}

	@Test
	void flowThreshold_withoutFlowControl_isEmpty() {
		assertThat(config("{\"memoryGib\": 1}").flowThreshold()).isEmpty();
	}

	@Test
	void limitsFifoPublishes_anSqsFifoQueue_isTrue() {
		assertThat(config("{\"technology\": \"sqs\", \"fifo\": true}").limitsFifoPublishes()).isTrue();
	}

	@Test
	void limitsFifoPublishes_aFifoQueueOfAnotherTechnology_isFalse() {
		assertThat(config("{\"technology\": \"rabbitmq\", \"fifo\": true}").limitsFifoPublishes()).isFalse();
	}

	private static MessageQueueConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), MessageQueueConfig.class);
	}

}
