package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run whose design has a queue says how old its messages were when processed and how
 * many it lost, and its availability counts the lost ones as failures (ADR-0084).
 */
class LostMessagesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_aQueueThatProcessesEveryMessage_givesItsAgeAndLosesNone() {
		JsonNode report = run(0.0, "", "{\"kind\": \"constant\", \"value\": 5}");
		JsonNode summary = report.required("summary");

		assertThat(summary.required("messageAgeP95Ms").doubleValue())
			.isEqualTo(ReferenceDesigns.summaryOf(report, "queue").required("p95MessageAgeMs").doubleValue());
		assertThat(summary.required("lostMessages").longValue()).isZero();
		assertThat(summary.required("availability").doubleValue())
			.isEqualTo(summary.required("successRate").doubleValue());
	}

	@Test
	void run_aConsumerThatFailsEveryMessage_losesThemAgainstItsAvailability() {
		JsonNode summary = run(1.0, "", "{\"kind\": \"constant\", \"value\": 5}").required("summary");
		long arrived = summary.required("arrived").longValue();
		long lostRequests = Math.round((1 - summary.required("availability").doubleValue()) * arrived);

		assertThat(summary.required("successRate").doubleValue()).isEqualTo(1.0);
		assertThat(summary.required("availability").doubleValue()).isLessThan(0.1);
		assertThat(summary.required("lostMessages").longValue()).isGreaterThanOrEqualTo(lostRequests);
		assertThat(summary.required("messageAgeP95Ms").isNull()).isTrue();
	}

	@Test
	void run_aQueueThatDropsWhenFull_losesTheDroppedMessagesAgainstItsAvailability() {
		JsonNode summary = run(0.0, ", \"delivery\": \"at_most_once\", \"maxDepth\": 5, \"consumers\": 1",
				"{\"kind\": \"constant\", \"value\": 200}")
			.required("summary");

		assertThat(summary.required("lostMessages").longValue()).isPositive();
		assertThat(summary.required("availability").doubleValue())
			.isLessThan(summary.required("successRate").doubleValue());
	}

	@Test
	void run_aDesignWithoutAQueue_hasNoMessageFigures() {
		JsonNode summary = this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "no queue"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 50, "durationS": 6, "warmupS": 1}}"""), RunRequest.of(null)).required("summary");

		assertThat(summary.has("lostMessages")).isFalse();
		assertThat(summary.has("availability")).isFalse();
		assertThat(summary.has("messageAgeP95Ms")).isFalse();
	}

	/**
	 * An API that publishes each request to a queue, whose consumer calls a mail API.
	 * @param errorRate the share of the mail API's calls that fail
	 * @param queueSettings more of the queue's settings, each after a comma
	 * @param workerTime the consumer's service time
	 * @return the report
	 */
	private JsonNode run(double errorRate, String queueSettings, String workerTime) {
		return this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "queue"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"},
				    {"id": "queue", "type": "message_queue", "config": {"maxDeliveries": 1%s}},
				    {"id": "worker", "type": "service", "config": {"serviceTimeMs": %s}},
				    {"id": "mail", "type": "external_api", "config": {"errorRate": %s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-queue", "source": "api", "target": "queue"},
				    {"id": "queue-worker", "source": "queue", "target": "worker"},
				    {"id": "worker-mail", "source": "worker", "target": "mail"}]},
				 "workload": {"rps": 50, "readRatio": 0.0, "durationS": 6, "warmupS": 1}}""".formatted(queueSettings,
				workerTime, errorRate)), RunRequest.of(null));
	}

}
