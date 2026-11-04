package io.archsim.model.catalog;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** A technology preset's settings and the documentation behind its numbers. */
class PresetTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Preset preset = new Preset("pg", "PostgreSQL", "postgresql", "Relational", "A database.",
			(ObjectNode) JSON.readTree("{\"replicas\": 3, \"maxConnections\": 100}"), List.of("sql"),
			Map.of("replicas", "https://example.org/replicas", "maxConnections", ""));

	@Test
	void setting_oneThePresetSets_isItsValue() {
		assertThat(this.preset.setting("replicas")).contains(JSON.readTree("3"));
	}

	@Test
	void setting_oneThePresetLeavesAlone_isEmpty() {
		assertThat(this.preset.setting("shards")).isEmpty();
	}

	@Test
	void source_aNumberItsDocumentationStates_isTheLink() {
		assertThat(this.preset.source("replicas")).contains("https://example.org/replicas");
	}

	@Test
	void source_anEmptyLink_countsAsNone() {
		assertThat(this.preset.source("maxConnections")).isEmpty();
	}

	@Test
	void config_changedByTheCaller_leavesThePresetAsItIs() {
		this.preset.config().put("replicas", 9);

		assertThat(this.preset.setting("replicas")).contains(JSON.readTree("3"));
	}

}
