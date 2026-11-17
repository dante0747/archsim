package io.archsim.model.document;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;

import static org.assertj.core.api.Assertions.assertThat;

/** A design's objectives, its messages' freshness among them (schema 1.10, ADR-0084). */
class ObjectivesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void load_aMessageAgeObjective_isTheP95AgeTarget() {
		ArchitectureDocument design = DesignDocuments.load(JSON.readTree(design(", \"messageAgeP95Ms\": 5000")));

		assertThat(design.objectives().messageAgeP95Ms()).isEqualTo(5000.0);
	}

	@Test
	void toJson_noMessageAgeObjective_leavesItOut() {
		ArchitectureDocument design = DesignDocuments.load(JSON.readTree(design("")));

		assertThat(DesignDocuments.toJson(design).path("objectives").has("messageAgeP95Ms")).isFalse();
	}

	private static String design(String objectives) {
		return """
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "objectives"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 10},
				 "objectives": {"latencyP95Ms": 300%s}}""".formatted(objectives);
	}

}
