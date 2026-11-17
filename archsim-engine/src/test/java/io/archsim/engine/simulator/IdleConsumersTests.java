package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue's consumers beyond its partitions sit idle, as only one consumer per partition
 * is active: the run reports them and its finding says why (ADR-0085).
 */
class IdleConsumersTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The part the queue's consumers call. */
	private static final String WORKER = """
			{"id": "worker", "type": "service"}""";

	/** A part that fails every call, so the queue's messages are dead-lettered. */
	private static final String FAILING_WORKER = """
			{"id": "worker", "type": "external_api", "config": {"errorRate": 1.0}}""";

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_moreConsumersThanPartitions_reportsTheIdleOnes() {
		JsonNode report = run("kafka", 4, 2);

		assertThat(ReferenceDesigns.summaryOf(report, "topic").path("idleConsumers").asLong()).isEqualTo(2);
	}

	@Test
	void run_moreConsumersThanPartitions_saysWhyInTheQueuesFinding() {
		JsonNode report = run("kafka", 4, 2);

		assertThat(finding(report).path("detail").stringValue()).contains("2 of the 4 consumers sat idle: only one "
				+ "consumer per partition is active, so 2 partitions keep at most 2 of them busy.");
		assertThat(finding(report).at("/evidence/idleConsumers").asLong()).isEqualTo(2);
		assertThat(finding(report).path("suggestion").stringValue()).isEqualTo("Add partitions or reduce consumers.");
	}

	@Test
	void run_idleConsumersOnAQueueBehind_advisesCatchingUpThroughPartitions() {
		JsonNode report = run("kafka", 4, 2, FAILING_WORKER);

		assertThat(finding(report).path("severity").stringValue()).isEqualTo("warning");
		assertThat(finding(report).path("suggestion").stringValue())
			.isEqualTo("Add partitions so the idle consumers get work, make consumers faster, or fix the failing "
					+ "downstream; lag is acceptable only while it drains within your freshness target.");
	}

	@Test
	void run_aSharedQueueWithMoreConsumersThanPartitions_reportsTheIdleOnesToo() {
		JsonNode report = run("sqs", 16, 8);

		assertThat(ReferenceDesigns.summaryOf(report, "topic").path("idleConsumers").asLong()).isEqualTo(8);
		assertThat(finding(report).path("detail").stringValue()).contains("8 of the 16 consumers sat idle");
	}

	@Test
	void run_noMoreConsumersThanPartitions_reportsNoIdleOnes() {
		JsonNode report = run("kafka", 2, 2);

		assertThat(ReferenceDesigns.summaryOf(report, "topic").has("idleConsumers")).isFalse();
		assertThat(finding(report).path("detail").stringValue()).doesNotContain("sat idle");
	}

	private static JsonNode finding(JsonNode report) {
		return report.required("findings")
			.valueStream()
			.filter((finding) -> "queue_lag".equals(finding.path("kind").stringValue("")))
			.findFirst()
			.orElseThrow();
	}

	private JsonNode run(String technology, int consumers, int partitions) {
		return run(technology, consumers, partitions, WORKER);
	}

	private JsonNode run(String technology, int consumers, int partitions, String worker) {
		return this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "topic"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"},
				    {"id": "topic", "type": "message_queue",
				     "config": {"technology": "%s", "consumers": %d, "partitions": %d}},
				    %s],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-topic", "source": "api", "target": "topic"},
				    {"id": "topic-worker", "source": "topic", "target": "worker"}]},
				 "workload": {"rps": 50, "readRatio": 0.0, "durationS": 6, "warmupS": 1}}""".formatted(technology,
				consumers, partitions, worker)), RunRequest.of(null));
	}

}
