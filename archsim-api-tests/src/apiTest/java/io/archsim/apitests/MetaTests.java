package io.archsim.apitests;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code GET /meta} says the server runs, beyond what the Python API said: ArchSim's
 * own version, beside the engine's (ADR-0076).
 */
@ApiTest(Profile.LOCAL)
class MetaTests {

	private static final String SEMANTIC_VERSION = "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?";

	@Test
	void meta_ofLocalServer_namesArchsimsVersion(ApiClient client) {
		JsonNode meta = client.get("/meta").json();

		assertThat(meta.path("version").asString()).matches(SEMANTIC_VERSION);
	}

}
