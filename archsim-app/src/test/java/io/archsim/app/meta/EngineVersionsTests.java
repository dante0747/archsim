package io.archsim.app.meta;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The versions the API reports are those of the engine that simulates the runs. */
class EngineVersionsTests {

	private static final JsonNode MANIFEST = JsonMapper.builder()
		.build()
		.readTree(Path.of(System.getProperty("archsim.repoRoot", "..")).resolve("parity/golden/manifest.json"));

	@Test
	void engine_always_isTheFrozenPythonEngines() {
		assertThat(EngineVersions.ENGINE).isEqualTo(MANIFEST.path("engineVersion").asString());
	}

	@Test
	void report_always_isTheFrozenPythonEnginesReportVersion() {
		assertThat(EngineVersions.REPORT).isEqualTo(MANIFEST.path("reportVersion").asString());
	}

}
