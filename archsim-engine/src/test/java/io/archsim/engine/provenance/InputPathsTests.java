package io.archsim.engine.provenance;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value a provenance path names, as the Python engine's {@code value_at} reads it.
 */
class InputPathsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode DESIGN = JSON.readTree("""
			{"workload": {"rps": 40},
			 "architecture": {"nodes": [{"id": "api", "config": {"replicas": 3}}],
			                  "edges": [{"id": "a-b", "config": {"latencyMs": 2}}]}}""");

	@ParameterizedTest
	@CsvSource({ "workload/rps, 40", "nodes/api/replicas, 3", "edges/a-b/latencyMs, 2", "nodes/api/cores, null",
			"nodes/ghost/replicas, null", "workload/pattern, null", "elsewhere/x, null" })
	void valueAt_path_isTheValueItNamesOrNull(String path, String value) {
		assertThat(InputPaths.valueAt(DESIGN, path)).isEqualTo(JSON.readTree(value));
	}

}
