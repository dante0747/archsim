package io.archsim.engine.parity;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue whose holding time is its consumers' alone read as the frozen engine's, whose
 * holding time also counted each publish. The figures are the CQRS template's events
 * topic in its golden base run: 587 publishes acknowledged in the window at 9.2072 ms
 * each, and 577 deliveries that ended in it at 29.1992 ms each, a mean of 19.1173 ms. Its
 * window holds the buckets at 0.5 s and 1.0 s, which acknowledge 100 and 487 of the
 * publishes; the bucket at 1.5 s is in the drain.
 */
class QueueHoldDeparturesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** No publisher blocked. */
	private static final String NOT_BLOCKED = "[0.0, 0.0, 0.0]";

	/**
	 * Publishers blocked 2,000 ms in the window's second bucket and 5,000 in the drain.
	 */
	private static final String BLOCKED = "[0.0, 2000.0, 5000.0]";

	private static final String HOLD = "/nodes/0/summary/meanHoldMs";

	private static final String CAPACITY = "/nodes/0/summary/capacityRps";

	private static final String LIMIT = "/nodes/0/summary/capacityLimit";

	@Test
	void asRecorded_aQueueWhosePublishesAndDeliveriesMakePythonsHoldingTime_readsAsPythons() {
		JsonNode python = python(19.1173);

		JsonNode read = QueueHoldDepartures.asRecorded(java(29.1992, NOT_BLOCKED), python);

		assertThat(read.at("/nodes/0/summary")).isEqualTo(python.at("/nodes/0/summary"));
		assertThat(read.at("/nodes/0/explain")).isEqualTo(python.at("/nodes/0/explain"));
		assertThat(read.at("/findings/0")).isEqualTo(python.at("/findings/0"));
	}

	@Test
	void asRecorded_aQueueWhoseHoldingTimeIsNotTheirMix_staysTheJavas() {
		JsonNode java = java(35.0, NOT_BLOCKED);

		assertThat(QueueHoldDepartures.asRecorded(java, python(19.1173))).isEqualTo(java);
	}

	@Test
	void asRecorded_aFindingThatDiffersBeyondItsCapacity_keepsTheRest() {
		JsonNode java = java(29.1992, NOT_BLOCKED);
		((ObjectNode) java.at("/findings/0")).put("detail", "On average 90% of its 12 slots were busy. At the observed "
				+ "29.2 ms per request it can sustain about 411 req/s; it received 334 req/s.");

		JsonNode read = QueueHoldDepartures.asRecorded(java, python(19.1173));

		assertThat(read.at("/findings/0/detail").stringValue()).startsWith("On average 90% of its 12 slots")
			.contains("19.1 ms per request it can sustain about 628 req/s");
	}

	// the window's buckets acknowledge 100 and 487 publishes, blocked 0 and 2,000 ms: a
	// mean block of 1,659.28 ms, which makes Python's 855.8871 ms (the buckets' plain
	// mean, 1,000 ms, would make 523.4129)
	@Test
	void asRecorded_aQueueWhosePublishersTheAlarmBlocked_readsAsPythonsAtTheirMeanBlock() {
		JsonNode read = QueueHoldDepartures.asRecorded(java(29.1992, BLOCKED), python(855.8871));

		assertThat(read.at(HOLD).doubleValue()).isEqualTo(855.8871);
	}

	// a block given to the bucket its publish was released in, not the one it was
	// acknowledged in, moves the deliveries' holding time Python's implies: 0.1 ms here
	@Test
	void asRecorded_aBlockedQueueWhoseDeliveriesWereHeldWithinTheBlocksPlacement_readsAsPythons() {
		JsonNode read = QueueHoldDepartures.asRecorded(java(29.3, BLOCKED), python(855.8871));

		assertThat(read.at(HOLD).doubleValue()).isEqualTo(855.8871);
	}

	// 0.8 ms, 2.7%, is beyond the 1% the blocks' placement allows
	@Test
	void asRecorded_aBlockedQueueWhoseDeliveriesWereHeldOtherwise_staysTheJavas() {
		JsonNode java = java(30.0, BLOCKED);

		assertThat(QueueHoldDepartures.asRecorded(java, python(855.8871))).isEqualTo(java);
	}

	// the drain's 5,000 ms would make a mean block of 3,195.95 ms and Python 1,630.8217
	// ms
	@Test
	void asRecorded_aBlockAfterTheWindow_countsForNothing() {
		JsonNode java = java(29.1992, BLOCKED);

		assertThat(QueueHoldDepartures.asRecorded(java, python(1630.8217))).isEqualTo(java);
	}

	@Test
	void asRecorded_aPartThatIsNotAQueue_staysTheJavas() {
		JsonNode java = java(29.1992, NOT_BLOCKED);
		((ObjectNode) java.at("/nodes/0")).put("type", "service");

		assertThat(QueueHoldDepartures.asRecorded(java, python(19.1173))).isEqualTo(java);
	}

	@Test
	void asRecorded_anotherPartInThatPlace_staysTheJavas() {
		JsonNode java = java(29.1992, NOT_BLOCKED);
		((ObjectNode) java.at("/nodes/0")).put("id", "audit");

		assertThat(QueueHoldDepartures.asRecorded(java, python(19.1173))).isEqualTo(java);
	}

	// with no delivery in the window the Java has no holding time, capacity or limit, and
	// Python's were its publishes'
	@Test
	void asRecorded_aQueueThatFinishedNoDeliveryInTheWindow_readsAsPythonsPublishes() {
		JsonNode java = java(29.1992, NOT_BLOCKED);
		ObjectNode summary = (ObjectNode) java.at("/nodes/0/summary");
		summary.putObject("counters").put("processed", 0);
		summary.putNull("meanHoldMs").putNull("capacityRps").putNull("capacityLimit");

		JsonNode python = python(9.2072);

		JsonNode read = QueueHoldDepartures.asRecorded(java, python);

		assertThat(List.of(read.at(HOLD), read.at(CAPACITY), read.at(LIMIT))).containsExactly(python.at(HOLD),
				python.at(CAPACITY), python.at(LIMIT));
	}

	// the events topic in the frozen engine at a holding time, with its capacity on its
	// 12 slots
	private static JsonNode python(double meanHoldMs) {
		double capacity = Math.round(12 * 1000 / meanHoldMs * 10_000) / 10_000.0;
		return JSON.readTree(String.format(Locale.ROOT, """
				{"nodes": [{"id": "events", "type": "message_queue",
				  "summary": {"completed": 587, "counters": {"processed": 577}, "meanServiceMs": 9.2072,
				              "meanHoldMs": %s, "capacityRps": %s, "capacityLimit": "slots"},
				  "explain": [{"id": "consumers", "result": 12.0},
				              {"id": "drain_rate", "substituted": "12 × 1000 ÷ %.2f ms", "result": %s}]}],
				 "findings": [{"kind": "hot", "nodeId": "events", "evidence": {"capacityRps": %s},
				   "detail": "On average 81%% of its 12 slots were busy. \
				At the observed %.1f ms per request it can sustain about %.0f req/s; it received 334 req/s."}]}""",
				meanHoldMs, capacity, meanHoldMs, capacity, capacity, meanHoldMs, capacity));
	}

	// the events topic in the Java at its consumers' holding time, in a window of two
	// half-second buckets that acknowledge 100 and 487 of its publishes, its publishers
	// blocked in each bucket as long on average as the series says
	private static JsonNode java(double meanHoldMs, String blocked) {
		double capacity = 12 * 1000 / meanHoldMs;
		return JSON.readTree("""
				{"window": {"warmupS": 0.5, "durationS": 1.5}, "timeseries": {"t": [0.5, 1.0, 1.5]},
				 "nodes": [{"id": "events", "type": "message_queue",
				  "summary": {"completed": 587, "counters": {"processed": 577}, "meanServiceMs": 9.2072,
				              "meanHoldMs": %s, "capacityRps": %s, "capacityLimit": "slots"},
				  "timeseries": {"completedRps": [200.0, 974.0, 1000.0], "publishBlockedMs": %s},
				  "explain": [{"id": "consumers", "result": 12.0},
				              {"id": "drain_rate", "substituted": "12 × 1000 ÷ 29.2 ms", "result": %s}]}],
				 "findings": [{"kind": "hot", "nodeId": "events", "evidence": {"capacityRps": %s},
				   "detail": "On average 81%% of its 12 slots were busy. \
				At the observed 29.2 ms per request it can sustain about 411 req/s; it received 334 req/s."}]}"""
			.formatted(meanHoldMs, capacity, blocked, capacity, capacity));
	}

}
