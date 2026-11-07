package io.archsim.engine.calibration.mapping;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlValueException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Calibration mappings read as the Python engine's {@code load_mapping} reads them, and
 * refused in its words, where the golden files do not reach. Every expected message is
 * the Python engine's.
 */
class MappingLoaderTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void load_text_isTheMappingItWrites() {
		CalibrationMapping mapping = MappingLoader.load("""
				nodes:
				  db:
				    workingSetGib: {gauge: max(pg_database_size_bytes), unit: bytes, reduce: max}
				    memoryWorkingSet: {working_set: sum(container_memory_working_set_bytes), inflight: q}
				workload:
				  rps: {rate: 'sum(rate(http_requests_total[1m]))'}
				""");

		assertThat(mapping.nodes().get("db")).containsExactly(
				Map.entry("workingSetGib",
						new GaugeSource("max(pg_database_size_bytes)", Reduction.MAX, false, MemoryUnit.BYTES)),
				Map.entry("memoryWorkingSet", new WorkingSetSource("sum(container_memory_working_set_bytes)",
						MemoryUnit.BYTES, null, "q", null)));
		assertThat(mapping.workload())
			.isEqualTo(Map.of(WorkloadInput.RPS, new RateSource("sum(rate(http_requests_total[1m]))", true)));
		assertThat(mapping.range()).isNull();
		assertThat(mapping.replay()).isNull();
	}

	@ParameterizedTest
	@MethodSource("mappingsThatDoNotFit")
	void load_mappingThatDoesNotFit_isRefusedWithWhereAndWhat(String mapping, String problem) {
		assertThatExceptionOfType(MappingException.class).isThrownBy(() -> MappingLoader.load(JSON.readTree(mapping)))
			.withMessage("the mapping is not valid: " + problem);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
			"a: [b]\\n[c]: 1\\n"     | found unhashable key at line 2, column 1
			"a: !!int [1]\\n"        | expected a scalar node, but found sequence at line 1, column 4
			"a: 1\\n---\\nb: 2\\n"   | expected one YAML document, found several
			""")
	void load_textThatIsNotValidYaml_isRefusedWithTheProblemAndWhereWithoutTheText(String text, String problem) {
		assertThatExceptionOfType(MappingException.class).isThrownBy(() -> MappingLoader.load(text.translateEscapes()))
			.withMessage("the mapping is not valid YAML: " + problem);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "[1, 2]\n", "just text\n" })
	void load_textThatIsNotAnObject_isRefused(String text) {
		assertThatExceptionOfType(MappingException.class).isThrownBy(() -> MappingLoader.load(text))
			.withMessage("the mapping must be a YAML or JSON object");
	}

	/**
	 * Mappings that do not fit, each with what the Python engine says is wrong and where.
	 */
	static Stream<Arguments> mappingsThatDoNotFit() {
		String derived = "(top level): Value error, nodes.a.";
		return Stream.of(
				Arguments.of("{\"workload\": {\"rpss\": {\"rate\": \"q\"}}}",
						"workload.rpss.[key]: Input should be 'rps'"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": 5}}}",
						"nodes.a.x: a node entry is one of {histogram}, {gauge}, {ratio}, {cpu, requests} or "
								+ "{workingSet, requests|inflight}"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": {\"gauge\": \"g\", \"histogram\": \"q\"}}}}",
						"nodes.a.x.histogram.gauge: Extra inputs are not permitted"),
				Arguments.of("{\"nodes\": {\"a\": {\"cpuPerRequest\": {\"gauge\": \"q\"}}}}",
						derived + "cpuPerRequest needs {cpu, requests}"),
				Arguments.of("{\"nodes\": {\"a\": {\"memoryWorkingSet\": {\"cpu\": \"q\", \"requests\": \"r\"}}}}",
						derived + "memoryWorkingSet needs {workingSet}"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": {\"cpu\": \"q\", \"requests\": \"r\"}}}}",
						derived + "x: CPU and working-set sources go under 'cpuPerRequest'"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": {\"workingSet\": \"q\", \"inflight\": \"r\"}}}}",
						derived + "x: CPU and working-set sources go under 'memoryWorkingSet'"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": {\"working_set\": \"q\"}}}}",
						"nodes.a.x.workingSet: Value error, memoryWorkingSet needs 'requests' (the request rate), "
								+ "'inflight' (requests held) or both, to fit the working set against"),
				Arguments.of("{\"nodes\": {\"a\": {\"x\": {\"ratio\": {\"hits\": \"q\"}}}}}",
						"nodes.a.x.ratio: Value error, ratio needs exactly 'hits' and 'misses' queries"),
				Arguments.of("{\"range\": {\"start\": \"a\", \"end\": \"b\", \"step\": 0}}",
						"range.step: Input should be greater than 0"));
	}

	@Test
	void memoryUnit_bytes_areTheUnitsPowersOfTwo() {
		assertThat(MemoryUnit.values()).extracting(MemoryUnit::bytes)
			.containsExactly(1.0, 1024.0, 1_048_576.0, 1_073_741_824.0);
		assertThat(MemoryUnit.named("GiB")).hasValue(MemoryUnit.GIB);
		assertThat(MemoryUnit.named("ms")).isEmpty();
	}

	@Test
	void load_yamlBeyondTheBounds_isPythonsValueError() {
		assertThatExceptionOfType(YamlValueException.class).isThrownBy(() -> MappingLoader.load("a: &a [*a]\n"))
			.withMessage("the YAML contains a recursive alias");
	}

	@Test
	void load_scalarPythonLooksUpInVain_isPythonsKeyError() {
		assertThatExceptionOfType(YamlKeyException.class).isThrownBy(() -> MappingLoader.load("x: !!bool maybe\n"))
			.withMessage("'maybe'");
	}

}
