package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database of several instances read as the frozen engine's where its busiest
 * instance's share of its cores is not the most of its instances': the golden reports and
 * the simulation parity's runs read it alike. The figures are a search cluster's at its
 * peak, three shards with a replica each, from one of the parity's runs.
 */
class BusiestInstanceDeparturesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode PYTHON = JSON.readTree("""
			{"nodes": [{"id": "search", "summary": {"capacityRps": 3919.0872, "capacityLimit": "cpu",
			  "cpuCapacityRps": 3919.0872, "serviceOnlyCapacityRps": 3919.0872},
			  "explain": [{"id": "slot_capacity"}, {"id": "cpu_capacity", "result": 3919.0872}]}]}""");

	@Test
	void asRecorded_aDatabaseThatRefusedOperations_isReadThoughItsBusiestInstanceIsBusyLessThanTheMean() {
		JsonNode read = BusiestInstanceDepartures.asRecorded(java(11093), PYTHON);

		assertThat(read.at("/nodes/0/summary").has("busiestInstanceCpuUtilization")).isFalse();
		assertThat(read.at("/nodes/0/summary/capacityRps").doubleValue()).isEqualTo(3919.0872);
	}

	@Test
	void asRecorded_aDatabaseThatRefusedNothingWithItsBusiestInstanceBusyLessThanTheMean_staysTheJavas() {
		JsonNode read = BusiestInstanceDepartures.asRecorded(java(0), PYTHON);

		assertThat(read.at("/nodes/0/summary/busiestInstanceCpuUtilization").doubleValue()).isEqualTo(0.8448);
	}

	// the cluster, its busiest instance's cores a little less busy than the mean over its
	// six instances, having refused this many operations
	private static JsonNode java(long refused) {
		return JSON.readTree("""
				{"nodes": [{"id": "search", "type": "database", "config": {"shards": 3, "readReplicas": 1},
				  "summary": {"rejectedTotal": %d, "busiestInstanceCpuUtilization": 0.8448,
				              "cpuCoreUtilization": 0.8463, "capacityRps": 1310.5905, "capacityLimit": "cpu",
				              "cpuCapacityRps": 1310.5905, "serviceOnlyCapacityRps": 1310.5905},
				  "explain": [{"id": "slot_capacity", "result": 4517.4567},
				              {"id": "cpu_capacity", "result": 1310.5905}]}]}""".formatted(refused));
	}

}
