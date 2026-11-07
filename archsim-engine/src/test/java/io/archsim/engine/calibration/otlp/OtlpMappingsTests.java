package io.archsim.engine.calibration.otlp;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.calibration.otlp.OtlpMapping.CpuAttribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * OTLP mappings read as the Python engine's {@code load_otlp_mapping} reads them, where
 * the golden files do not reach. Every expected message is the Python engine's.
 */
class OtlpMappingsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String CPU_TIME = "the OTLP mapping's 'cpuTime' is {attribute: <span attribute name>, "
			+ "unit: s|ms|us|ns}";

	@Test
	void load_cpuTimeWithoutAUnit_isInMilliseconds() {
		OtlpMapping mapping = OtlpMappings.load(JSON.readTree("{\"cpuTime\": {\"attribute\": \"x\"}}")).orElseThrow();

		assertThat(mapping.cpuTime()).isEqualTo(new CpuAttribute("x", CpuUnit.MILLISECONDS));
		assertThat(mapping.services()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"databases\": \"\"}", "{\"cpuTime\": null}" })
	void load_sectionsNotTruthyOrNull_areEmpty(String mapping) {
		OtlpMapping read = OtlpMappings.load(JSON.readTree(mapping)).orElseThrow();

		assertThat(read).isEqualTo(new OtlpMapping(Map.of(), Map.of(), null));
	}

	@Test
	void load_null_isNone() {
		assertThat(OtlpMappings.load(NODES.nullNode())).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("mappingsItRefuses")
	void load_mappingItCannotUse_isRefusedInThePythonEnginesWords(String mapping, String message) {
		assertThatIllegalArgumentException().isThrownBy(() -> OtlpMappings.load(JSON.readTree(mapping)))
			.withMessage(message);
	}

	@Test
	void load_textWithUnknownKeys_namesThemSorted() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OtlpMappings.load(NODES.stringNode("services: {a: b}\nzeta: 2\nother: 1\n")))
			.withMessage("the OTLP mapping has unknown keys (other, zeta): it takes services, databases and cpuTime");
	}

	@Test
	void load_textThatIsNotAnObject_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> OtlpMappings.load(NODES.stringNode("[1, 2]\n")))
			.withMessage("the OTLP mapping must be an object: {services: {...}, databases: {...}}");
	}

	@Test
	void load_textThatIsNotValidYaml_isRefusedWithTheProblemAndWhere() {
		assertThatIllegalArgumentException().isThrownBy(() -> OtlpMappings.load(NODES.stringNode("a: {b: [1}\n")))
			.withMessageStartingWith("the OTLP mapping is not valid YAML: ")
			.withMessageEndingWith(" at line 1, column 10");
	}

	/** Mappings the Python engine refuses, each with its words. */
	static Stream<Arguments> mappingsItRefuses() {
		return Stream.of(Arguments.of("{\"cpuTime\": {\"attribute\": \"  \"}}", CPU_TIME),
				Arguments.of("{\"cpuTime\": {\"attribute\": \"x\", \"unit\": null}}", CPU_TIME),
				Arguments.of("{\"cpuTime\": {\"attribute\": \"x\", \"extra\": 1}}", CPU_TIME),
				Arguments.of("{\"cpuTime\": \"x\"}", CPU_TIME),
				Arguments.of("{\"cpuTime\": {\"attribute\": \"" + "y".repeat(201) + "\"}}", CPU_TIME),
				Arguments.of("{\"services\": {\"a\": 1}}", "the OTLP mapping's 'services' must map names to part ids"));
	}

}
