package io.archsim.engine.technology;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A technology's name, as the Python engine gives it: every technology it knows, and the
 * other ways it names one ({@code analysis/cases.json}, written by
 * {@code scripts/export_analysis_cases.py}).
 */
class TechnologyNamesTests {

	private final TechnologyNames names = new TechnologyNames(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> of_eachTechnologyOfTheCases_isThePythonEnginesName() throws IOException {
		return names().map((entry) -> DynamicTest.dynamicTest(
				entry.path("type").asString() + " " + entry.path("technology").asString(),
				() -> assertThat(this.names.of(entry.path("type").asString(), entry.path("technology").asString()))
					.isEqualTo(Optional.ofNullable(entry.path("name").asString(null)))));
	}

	@Test
	void of_aTechnologyTheEngineKnows_isItsName() {
		assertThat(this.names.of("database", "postgresql")).contains("PostgreSQL");
	}

	@Test
	void of_aTechnologyTheEngineKnows_isNamedWhateverItsType() {
		assertThat(this.names.of("nope", "redis")).contains("Redis");
	}

	@Test
	void of_aTechnologyOfAPreset_isThePresetsName() {
		assertThat(this.names.of("external_api", "payments")).contains("Payments API");
	}

	@Test
	void of_anyOtherTechnology_isItsWordsCapitalized() {
		assertThat(this.names.of("service", "my_custom-tech")).contains("My Custom Tech");
		assertThat(this.names.of("service", "\u00F1and\u00FA_db")).contains("\u00D1and\u00FA Db");
	}

	@Test
	void of_noTechnology_isNone() {
		assertThat(this.names.of("service", "")).isEmpty();
	}

	private static Stream<JsonNode> names() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				TechnologyNamesTests.class.getResourceAsStream("/io/archsim/engine/analysis/cases.json"),
				"cases.json")) {
			return JsonMapper.builder().build().readTree(in).path("technologyNames").valueStream().toList().stream();
		}
	}

}
