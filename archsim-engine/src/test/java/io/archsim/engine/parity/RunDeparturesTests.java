package io.archsim.engine.parity;

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

	// the baselines' figures of a run, its parts' values as given
	private static JsonNode figures(String nodes) {
		return JSON.readTree("{\"summary\": {}, \"nodes\": " + nodes + "}");
	}

}
