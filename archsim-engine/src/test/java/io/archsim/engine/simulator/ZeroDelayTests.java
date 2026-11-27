package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work that takes no time goes on in a loop, not deeper on the Java stack: a consumer
 * that works 0 ms on each message simulates a poll's whole batch, a poll's batches from
 * many partitions, and a backlog it drains one message at a time, at any size. Each went
 * one call deeper than the last, and thousands of them overflowed the stack.
 */
class ZeroDelayTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped(),
			System::nanoTime);

	@Test
	void run_aConsumerThatPollsLargeBatchesAndWorksNoTime_processesEveryMessage() {
		JsonNode report = this.simulator.run(kafka(1, """
				"consumerTimeMs": {"kind": "constant", "value": 0},
				"fetchOverheadMs": {"kind": "constant", "value": 250}"""), RunRequest.of(null));

		assertThat(backlogAtEnd(report)).isZero();
	}

	@Test
	void run_aConsumerThatPollsManyPartitionsAndWorksNoTime_processesEveryMessage() {
		JsonNode report = this.simulator.run(kafka(2000, """
				"partitioning": "round_robin", "consumerTimeMs": {"kind": "constant", "value": 0},
				"fetchOverheadMs": {"kind": "constant", "value": 250}"""), RunRequest.of(null));

		assertThat(backlogAtEnd(report)).isZero();
	}

	@Test
	void run_aConsumerThatTakesOneMessageAtATimeAndDrainsABacklogInNoTime_processesEveryMessage() {
		// one message in a thousand takes up to 2 s, the rest none: the backlog that
		// builds behind a slow one is drained at 0 ms a message
		JsonNode report = this.simulator.run(kafka(1, """
				"consumerTimeMs": {"kind": "empirical", "quantiles": [[0, 0], [0.999, 0], [1, 2000]]}"""),
				RunRequest.of(null));

		assertThat(backlogAtEnd(report)).isZero();
	}

	private static long backlogAtEnd(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "topic").required("backlogAtEnd").asLong();
	}

	/**
	 * Clients publishing 20,000 messages a second for 3 s to a Kafka topic whose one
	 * consumer calls nothing.
	 * @param partitions the topic's partitions
	 * @param consuming the topic's other settings, as JSON members
	 * @return the design
	 */
	private static JsonNode kafka(int partitions, String consuming) {
		return JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "topic", "type": "message_queue",
				              "config": {"technology": "kafka", "partitions": %d, "consumers": 1, "maxDepth": 1000000,
				                         %s}}],
				   "edges": [{"id": "clients-topic", "source": "clients", "target": "topic"}]},
				 "workload": {"seed": 7, "warmupS": 1, "durationS": 3, "rps": 20000,
				              "arrivalProcess": "deterministic"}}""".formatted(partitions, consuming));
	}

}
