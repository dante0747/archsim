package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Java run read as the frozen engine's from what the baselines keep, and the departures
 * they keep too little of to read so. {@link RunFiguresTests} reads the golden files'
 * runs whole.
 */
class RunDeparturesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode NO_FIGURES = JSON.readTree("{\"summary\": {}, \"nodes\": {}}");

	@Test
	void asRecorded_aQueueWithMoreConsumersThanPartitions_leavesItsIdleConsumersOut() {
		JsonNode java = JSON.readTree("""
				{"summary": {}, "nodes": [{"id": "orders", "type": "message_queue", "config": {"consumers": 6},
				  "summary": {"effectiveConsumers": 4, "idleConsumers": 2}}]}""");

		JsonNode read = RunDepartures.asRecorded(java, NO_FIGURES, MissingNode.getInstance());

		assertThat(read.at("/nodes/0/summary").toString()).isEqualTo("{\"effectiveConsumers\":4}");
	}

	@Test
	void asRecorded_aCacheThatRefusedLookups_hasTheFrozenEnginesHitRatioOverItsHitsAndMisses() {
		JsonNode read = RunDepartures.asRecorded(cache(), figures("{\"cache\": {\"hitRatio\": 0.9}}"),
				MissingNode.getInstance());

		assertThat(read.at("/nodes/0/summary/hitRatio").doubleValue()).isEqualTo(0.9);
	}

	@Test
	void asRecorded_aCacheWhoseFrozenHitRatioIsNotItsHitsOverHitsAndMisses_keepsTheJavasHitRatio() {
		JsonNode read = RunDepartures.asRecorded(cache(), figures("{\"cache\": {\"hitRatio\": 0.5}}"),
				MissingNode.getInstance());

		assertThat(read.at("/nodes/0/summary/hitRatio").doubleValue()).isEqualTo(0.45);
	}

	@Test
	void asRecorded_aDatabaseOfSeveralInstances_hasTheFrozenEnginesCeilingAndItsFormulaOfTheWorkPerRequest() {
		JsonNode read = RunDepartures.asRecorded(cluster(), figures(frozenCluster(", \"meanServiceMs\": 4.5084")),
				MissingNode.getInstance());

		assertThat(read.at("/nodes/0/summary").has("busiestInstanceCpuUtilization")).isFalse();
		assertThat(read.at("/nodes/0/explain/1").toString()).isEqualTo("{\"id\":\"cpu_capacity\","
				+ "\"title\":\"Capacity from CPU\",\"formula\":\"CPU ceiling = cores × instances × 1000 ÷ CPU time per "
				+ "request\",\"substituted\":\"from the CPU spec and 4.508 ms of work per request\","
				+ "\"result\":3919.0872,\"unit\":\"req/s\",\"note\":null,\"source\":null,"
				+ "\"metric\":\"cpuUtilization\"}");
	}

	@Test
	void asRecorded_aDatabaseWhoseFrozenFiguresGiveNoCpuCeilingFormula_staysTheJavas() {
		JsonNode read = RunDepartures.asRecorded(cluster(), figures(frozenCluster("")), MissingNode.getInstance());

		assertThat(read.at("/nodes/0/summary/busiestInstanceCpuUtilization").doubleValue()).isEqualTo(0.85);
	}

	// the Java's topic held its 600 deliveries 240 ms each and its 600 publishes
	// 10 ms, so the frozen engine's 125 ms, which counted both, writes its formulas
	@Test
	void asRecorded_aQueueOfTheFrozenEnginesFigures_hasItsFormulasAtTheFrozenHoldingTime() {
		JsonNode read = RunDepartures.asRecorded(topic(), figures("""
				{"orders": {"arrivals": 600, "arrivalRps": 60.0, "slots": 16, "completed": 600, "meanServiceMs": 10.0,
				            "meanHoldMs": 125.0, "capacityRps": 128.0, "capacityLimit": "slots", "meanInSystem": 0.6,
				            "meanQueueWaitMs": 100.0, "utilization": 0.9}}"""), MissingNode.getInstance());

		assertThat(List.of(formula(read, "drain_rate").path("substituted").stringValue(),
				formula(read, "drain_rate").path("result").doubleValue(),
				formula(read, "slot_capacity").path("result").doubleValue(),
				formula(read, "utilization").path("result").doubleValue()))
			.containsExactly("16 × 1000 ÷ 125 ms", 128.0, 128.0, 0.4688);
		assertThat(formula(read, "mmc_wait").path("substituted").stringValue()).startsWith("C(16, 7.5) = ");
		assertThat(read.at("/nodes/0/summary/meanHoldMs").doubleValue()).isEqualTo(125.0);
	}

	@Test
	void unread_aRunWhereACacheRefusedLookups_namesTheDeparture() {
		JsonNode java = JSON.readTree("""
				{"nodes": [{"id": "db", "summary": {}},
				           {"id": "cache", "summary": {"counters": {"refusedLookups": 3}}}]}""");

		assertThat(RunDepartures.unread(java)).containsExactly(RunDepartures.REFUSED_LOOKUPS);
	}

	@Test
	void unread_aCacheThatRefusedNoLookup_isNone() {
		JsonNode java = JSON.readTree("""
				{"nodes": [{"id": "cache", "summary": {"counters": {"refusedLookups": 0}}}]}""");

		assertThat(RunDepartures.unread(java)).isEmpty();
	}

	// a cache that refused 10 of its 20 lookups, hitting 9: a hit ratio of 0.45, and 0.9
	// over its hits and misses alone
	private static JsonNode cache() {
		return JSON.readTree("""
				{"summary": {}, "nodes": [{"id": "cache", "type": "cache",
				  "summary": {"hitRatio": 0.45, "counters": {"hits": 9, "misses": 1, "refusedLookups": 10}}}]}""");
	}

	// a search cluster of three shards with a replica each, its ceiling its busiest
	// instance's
	private static JsonNode cluster() {
		return JSON.readTree("""
				{"summary": {}, "nodes": [{"id": "search", "type": "database",
				  "config": {"shards": 3, "readReplicas": 1},
				  "summary": {"busiestInstanceCpuUtilization": 0.85, "cpuCoreUtilization": 0.8463,
				              "capacityRps": 1310.5905, "capacityLimit": "cpu", "cpuCapacityRps": 1310.5905,
				              "serviceOnlyCapacityRps": 1310.5905},
				  "explain": [{"id": "slot_capacity", "result": 4517.4567},
				              {"id": "cpu_capacity", "result": 1310.5905}]}]}""");
	}

	// the frozen engine's figures of the cluster, spread over its instances, with more
	private static String frozenCluster(String more) {
		return """
				{"search": {"capacityRps": 3919.0872, "capacityLimit": "cpu", "cpuCapacityRps": 3919.0872,
				            "serviceOnlyCapacityRps": 3919.0872%s}}""".formatted(more);
	}

	// a topic of 16 consumer slots whose deliveries the Java held 240 ms each, its
	// formulas at that holding time
	private static JsonNode topic() {
		return JSON.readTree("""
				{"summary": {}, "window": {"warmupS": 5.0, "durationS": 15.0, "measuredS": 10.0},
				 "timeseries": {"t": []},
				 "nodes": [{"id": "orders", "type": "message_queue", "category": "messaging", "label": "orders",
				  "summary": {"arrivals": 600, "arrivalRps": 60.0, "slots": 16, "completed": 600,
				              "counters": {"processed": 600}, "meanServiceMs": 10.0, "meanHoldMs": 240.0,
				              "capacityRps": 66.6667, "capacityLimit": "slots", "meanInSystem": 0.6,
				              "latencyMs": {"mean": 10.0}, "meanQueueWaitMs": 100.0, "utilization": 0.9},
				  "explain": [{"id": "arrival_rate", "result": 60.0}, {"id": "slot_capacity", "result": 66.6667},
				              {"id": "utilization", "result": 0.9}, {"id": "littles_law", "result": 0.6},
				              {"id": "mmc_wait", "result": 1.0}, {"id": "consumers", "result": 16.0},
				              {"id": "drain_rate", "title": "Consumer throughput",
				               "formula": "messages/s = effective consumers × 1000 ÷ mean processing time",
				               "substituted": "16 × 1000 ÷ 240 ms", "result": 66.6667, "unit": "msg/s",
				               "note": null, "source": null, "metric": "lag"}]}]}""");
	}

	// a part's formula in a report, by its id
	private static JsonNode formula(JsonNode report, String id) {
		return report.at("/nodes/0/explain")
			.valueStream()
			.filter((formula) -> formula.path("id").stringValue("").equals(id))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no formula " + id));
	}

	// the baselines' figures of a run, its parts' values as given
	private static JsonNode figures(String nodes) {
		return JSON.readTree("{\"summary\": {}, \"nodes\": " + nodes + "}");
	}

}
