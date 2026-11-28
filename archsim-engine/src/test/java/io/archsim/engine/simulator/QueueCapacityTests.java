package io.archsim.engine.simulator;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * A queue's consumers are held by consumer processing only: a delivery holds a consumer
 * slot while it consumes its message and makes the calls the message makes, and a publish
 * holds none. On the Kafka template, simulated once for every test, its topic's time per
 * message, its capacity, its consumer throughput formula, its finding and the instant
 * estimate then agree; a queue that finished no delivery in its window has no holding
 * time, and nothing that needs one.
 */
@TestInstance(Lifecycle.PER_CLASS)
class QueueCapacityTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TOPIC = "orders-topic";

	/** The template's writes, a tenth of its requests, carry its orders. */
	private static final double WRITE_SHARE = 0.1;

	private static final double MS_PER_SECOND = 1000;

	/**
	 * How near Little's law holds the measured holding time: the busy slots and the
	 * messages processed are the window's, and a message held across its edges counts in
	 * one of them and not the other.
	 */
	private static final double LITTLES_LAW_PERCENT = 5;

	/**
	 * How near the instant estimate's capacity holds the simulated one: the estimate's
	 * latency is held to the simulated within as much ({@code EstimateAgreementTests}).
	 */
	private static final double ESTIMATE_PERCENT = 15;

	/**
	 * How far a formula's result, written from the holding time rounded to 4 decimals,
	 * may lie from the capacity, computed from the unrounded one.
	 */
	private static final double ROUNDING = 1e-3;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private final JsonNode report = this.simulator.run(ReferenceDesigns.example("kafka-order-pipeline.json"),
			RunRequest.of(null));

	// Little's law: the slots busy on average are the messages processed a
	// second times the time each of them holds a slot
	@Test
	void run_aKafkaPipeline_givesItsTopicTheHoldingTimeItsBusySlotsMake() {
		JsonNode topic = ReferenceDesigns.summaryOf(this.report, TOPIC);
		double processedPerSecond = topic.at("/counters/processed").doubleValue()
				/ this.report.at("/window/measuredS").doubleValue();
		double busySlots = topic.required("utilization").doubleValue() * topic.required("slots").doubleValue();

		assertThat(topic.required("meanHoldMs").doubleValue()).isCloseTo(busySlots * MS_PER_SECOND / processedPerSecond,
				withinPercentage(LITTLES_LAW_PERCENT));
	}

	// the estimate saturates the template at the load whose writes fill the topic
	@Test
	void run_aKafkaPipeline_givesItsTopicTheCapacityTheEstimateGivesIt() {
		JsonNode topic = ReferenceDesigns.summaryOf(this.report, TOPIC);
		double estimated = this.report.at("/estimate/saturationRps").doubleValue() * WRITE_SHARE;

		assertThat(this.report.at("/estimate/busiest/id").stringValue()).isEqualTo(TOPIC);
		assertThat(topic.required("capacityRps").doubleValue()).isCloseTo(estimated,
				withinPercentage(ESTIMATE_PERCENT));
	}

	@Test
	void run_aKafkaPipeline_explainsAndReportsItsTopicsCapacityAsOneFigure() {
		double capacity = ReferenceDesigns.summaryOf(this.report, TOPIC).required("capacityRps").doubleValue();

		assertThat(formula(this.report, TOPIC, "drain_rate").required("result").doubleValue()).isCloseTo(capacity,
				within(ROUNDING));
		assertThat(topicFinding().at("/evidence/capacityRps").doubleValue()).isEqualTo(capacity);
	}

	// a consumer that takes ten minutes on a message finishes none in a run of 3 s: the
	// topic published, but no delivery ended in its window
	@Test
	void run_aQueueThatFinishedNoDeliveryInItsWindow_hasNoHoldingTimeNorWhatNeedsOne() {
		JsonNode quiet = this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "topic", "type": "message_queue",
				              "config": {"technology": "kafka", "partitions": 1, "consumers": 1, "maxDepth": 1000000,
				                         "consumerTimeMs": {"kind": "constant", "value": 600000}}}],
				   "edges": [{"id": "clients-topic", "source": "clients", "target": "topic"}]},
				 "workload": {"seed": 7, "warmupS": 1, "durationS": 3, "rps": 100}}"""), RunRequest.of(null));
		JsonNode topic = ReferenceDesigns.summaryOf(quiet, "topic");

		assertThat(topic.required("completed").longValue()).isPositive();
		assertThat(List.of(topic.path("meanHoldMs"), topic.path("capacityRps"), topic.path("capacityLimit")))
			.allMatch(JsonNode::isNull);
		assertThat(node(quiet, "topic").required("explain")
			.valueStream()
			.map((formula) -> formula.path("id").stringValue("")))
			.doesNotContain("slot_capacity", "utilization", "mmc_wait", "drain_rate");
		assertThat(quiet.required("findings").valueStream().map((finding) -> finding.path("detail").stringValue("")))
			.noneMatch((detail) -> detail.contains("can sustain"));
	}

	// a part in a report
	private static JsonNode node(JsonNode report, String id) {
		return report.required("nodes")
			.valueStream()
			.filter((node) -> node.required("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no part " + id));
	}

	// a part's formula
	private static JsonNode formula(JsonNode report, String id, String formula) {
		return node(report, id).required("explain")
			.valueStream()
			.filter((each) -> each.required("id").stringValue().equals(formula))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no formula " + formula + " for " + id));
	}

	// the finding that says what the topic can sustain
	private JsonNode topicFinding() {
		return this.report.required("findings")
			.valueStream()
			.filter((finding) -> finding.path("nodeId").stringValue("").equals(TOPIC)
					&& finding.at("/evidence/capacityRps").isNumber())
			.findFirst()
			.orElseThrow(() -> new AssertionError("no finding with " + TOPIC + "'s capacity"));
	}

}
