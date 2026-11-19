package io.archsim.benchmarks.baselines;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knee by bottleneck arithmetic, on hand-made designs: a service of one core that
 * takes 0.5 ms a request, in front of a database of two cores and ten connections whose
 * reads take 1 ms and whose writes take 2 ms and a 3 ms commit flush, half of its time on
 * a core.
 */
class BottleneckArithmeticTests {

	private static final String CLIENTS = "{\"id\": \"clients\", \"type\": \"traffic_source\", \"config\": {}}";

	private static final String SERVICE = """
			{"id": "api", "type": "service",
			 "config": {"cpuCores": 1, "serviceTimeMs": {"kind": "constant", "value": 0.5}}}""";

	private static final String DATABASE = """
			{"id": "db", "type": "database",
			 "config": {"maxConnections": 10, "cpuCores": 2, "cpuShare": 0.5,
			  "readServiceTimeMs": {"kind": "constant", "value": 1.0},
			  "writeServiceTimeMs": {"kind": "constant", "value": 2.0},
			  "commitFlushMs": {"kind": "constant", "value": 3.0}}}""";

	private static final String LINK = """
			{"id": "e-%s", "source": "%s", "sourcePort": "%s", "target": "%s",
			 "config": {"requestTypes": [%s], "latencyMs": {"kind": "constant", "value": %s}}}""";

	private static final String TO_SERVICE = link("clients", "api", 0.1);

	private static final String TO_DATABASE = link("api", "db", 0.2);

	private static final double NO_CACHE = 0.0;

	// half reads: a request takes 0.75 ms of the database's cores and 3 ms of a
	// connection
	@Test
	void of_aServiceAndItsDatabase_dividesWhatEachHasByWhatARequestTakesOfIt() {
		ObjectNode design = design(List.of(CLIENTS, SERVICE, DATABASE), List.of(TO_SERVICE, TO_DATABASE), 0.5, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("limits")).isEqualTo(JsonText.read("""
				[{"part": "api", "limit": "cpu", "units": 1, "msPerRequest": 0.5, "capacityRps": 2000.0,
				  "onRequestPath": true},
				 {"part": "db", "limit": "cpu", "units": 2, "msPerRequest": 0.75, "capacityRps": 2666.7,
				  "onRequestPath": true},
				 {"part": "db", "limit": "connections", "units": 10, "msPerRequest": 3.0, "capacityRps": 3333.3,
				  "onRequestPath": true}]
				"""));
	}

	@Test
	void of_aServiceAndItsDatabase_takesTheSmallestCapacityAsTheKnee() {
		ObjectNode design = design(List.of(CLIENTS, SERVICE, DATABASE), List.of(TO_SERVICE, TO_DATABASE), 0.5, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(2000.0);
		assertThat(arithmetic.get("limitedBy")).isEqualTo(JsonText.read("{\"part\": \"api\", \"limit\": \"cpu\"}"));
	}

	// the curve's factor at its highest rate, the calibrated one: 0.5 ms x 1.5
	@Test
	void of_aServiceTimeByLoad_takesTheTimeAtTheHighestCalibratedRate() {
		String service = """
				{"id": "api", "type": "service",
				 "config": {"cpuCores": 1, "serviceTimeMs": {"kind": "constant", "value": 0.5},
				  "serviceTimeByLoad": [{"rps": 400.0, "factor": 1.5}, {"rps": 50.0, "factor": 3.0}]}}""";
		ObjectNode design = design(List.of(CLIENTS, service, DATABASE), List.of(TO_SERVICE, TO_DATABASE), 0.5, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(1333.3);
	}

	// a worker is held for the service's 0.5 ms, the link both ways and the query's 3 ms
	@Test
	void of_workersThatWaitForTheirCalls_dividesThemByTheTimeARequestHoldsOne() {
		String service = """
				{"id": "api", "type": "service",
				 "config": {"cpuCores": 1, "workersPerReplica": 2, "blockingDownstream": true,
				  "serviceTimeMs": {"kind": "constant", "value": 0.5}}}""";
		ObjectNode design = design(List.of(CLIENTS, service, DATABASE), List.of(TO_SERVICE, TO_DATABASE), 0.5, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("limits").get(1)).isEqualTo(JsonText.read("""
				{"part": "api", "limit": "workers", "units": 2, "msPerRequest": 3.9, "capacityRps": 512.8,
				 "onRequestPath": true}
				"""));
		assertThat(arithmetic.get("limitedBy")).isEqualTo(JsonText.read("{\"part\": \"api\", \"limit\": \"workers\"}"));
	}

	// every write updates the one row: its lock is held for the write and its flush
	@Test
	void of_writesToOneKey_dividesTheRowsLockByTheTimeAWriteHoldsIt() {
		ObjectNode design = design(List.of(CLIENTS, SERVICE, DATABASE), List.of(TO_SERVICE, TO_DATABASE), 0.5, 1);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("limits").get(3)).isEqualTo(JsonText.read("""
				{"part": "db", "limit": "rowLock", "units": 1, "msPerRequest": 2.5, "capacityRps": 400.0,
				 "onRequestPath": true}
				"""));
		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(400.0);
	}

	// a quarter of the lookups miss: 1.25 commands a request, and a quarter of a read
	@Test
	void of_aCacheInFrontOfTheDatabase_sendsTheDatabaseTheMissesOnly() {
		String cache = """
				{"id": "cache", "type": "cache",
				 "config": {"cpuCores": 1, "serviceTimeMs": {"kind": "exponential", "mean": 0.04}}}""";
		ObjectNode design = design(List.of(CLIENTS, SERVICE, cache, DATABASE),
				List.of(TO_SERVICE, reads("api", "out", "cache", 0.05), reads("cache", "miss", "db", 0.2)), 1.0, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, 0.25);

		assertThat(arithmetic.get("limits")).isEqualTo(JsonText.read("""
				[{"part": "api", "limit": "cpu", "units": 1, "msPerRequest": 0.5, "capacityRps": 2000.0,
				  "onRequestPath": true},
				 {"part": "cache", "limit": "cpu", "units": 1, "msPerRequest": 0.05, "capacityRps": 20000.0,
				  "onRequestPath": true},
				 {"part": "db", "limit": "cpu", "units": 2, "msPerRequest": 0.125, "capacityRps": 16000.0,
				  "onRequestPath": true},
				 {"part": "db", "limit": "connections", "units": 10, "msPerRequest": 0.25, "capacityRps": 40000.0,
				  "onRequestPath": true}]
				"""));
	}

	// a consumer slot is held for its own 0.1 ms, the link both ways and the insert's 5
	// ms
	@Test
	void of_workBehindAQueue_isListedButDoesNotBoundTheKnee() {
		ObjectNode design = design(List.of(CLIENTS, SERVICE, queue(3), DATABASE),
				List.of(TO_SERVICE, link("api", "kafka", 0.0), link("kafka", "db", 0.2)), 0.0, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("limits")).isEqualTo(JsonText.read("""
				[{"part": "api", "limit": "cpu", "units": 1, "msPerRequest": 0.5, "capacityRps": 2000.0,
				  "onRequestPath": true},
				 {"part": "kafka", "limit": "consumers", "units": 3, "msPerRequest": 5.5, "capacityRps": 545.5,
				  "onRequestPath": false},
				 {"part": "db", "limit": "cpu", "units": 2, "msPerRequest": 1.0, "capacityRps": 2000.0,
				  "onRequestPath": false},
				 {"part": "db", "limit": "connections", "units": 10, "msPerRequest": 5.0, "capacityRps": 2000.0,
				  "onRequestPath": false}]
				"""));
		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(2000.0);
	}

	// the first Kafka runs calibrated no consumer concurrency, which the model reads as
	// one message at a time
	@Test
	void of_aQueueWithoutItsConsumersConcurrency_takesOneSlotAConsumer() {
		ObjectNode design = design(List.of(CLIENTS, SERVICE, queue(null), DATABASE),
				List.of(TO_SERVICE, link("api", "kafka", 0.0), link("kafka", "db", 0.2)), 0.0, 100);

		ObjectNode arithmetic = BottleneckArithmetic.of(design, NO_CACHE);

		assertThat(arithmetic.get("limits").get(1)).isEqualTo(JsonText.read("""
				{"part": "kafka", "limit": "consumers", "units": 1, "msPerRequest": 5.5, "capacityRps": 181.8,
				 "onRequestPath": false}
				"""));
	}

	private static String queue(@Nullable Integer concurrency) {
		return """
				{"id": "kafka", "type": "message_queue",
				 "config": {"consumers": 1, "consumerConcurrency": %s,
				  "publishTimeMs": {"kind": "constant", "value": 0.4},
				  "consumerTimeMs": {"kind": "constant", "value": 0.1}}}""".formatted(concurrency);
	}

	// a link that carries reads and writes
	private static String link(String source, String target, double latency) {
		return LINK.formatted(target, source, "out", target, "\"read\", \"write\"", latency);
	}

	// a link that carries reads alone, from a port of its source
	private static String reads(String source, String port, String target, double latency) {
		return LINK.formatted(target, source, port, target, "\"read\"", latency);
	}

	private static ObjectNode design(List<String> nodes, List<String> edges, double readRatio, int keys) {
		JsonNode design = JsonText.read("""
				{"architecture": {"nodes": [%s], "edges": [%s]}, "workload": {"readRatio": %s, "keyCount": %s}}
				""".formatted(String.join(", ", nodes), String.join(", ", edges), readRatio, keys));
		return (ObjectNode) design;
	}

}
