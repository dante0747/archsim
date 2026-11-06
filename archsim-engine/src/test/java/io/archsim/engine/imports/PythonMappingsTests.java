package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;

/**
 * Python's ways with a value read as a mapping, failing in Python's words where Python
 * fails.
 */
class PythonMappingsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void dict_valueThatCountsAsFalse_isAnEmptyMapping() {
		assertThat(PythonMappings.dict(json("null"), "get")).isEmpty();
		assertThat(PythonMappings.dict(json("[]"), "get")).isEmpty();
	}

	@Test
	void dict_valueThatCountsAsTrueAndIsNoMapping_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class)
			.isThrownBy(() -> PythonMappings.dict(json("[1]"), "items"))
			.withMessage("'list' object has no attribute 'items'");
	}

	@Test
	void mapping_mapping_isItself() {
		ObjectNode value = (ObjectNode) json("{\"a\": 1}");

		assertThat(PythonMappings.mapping(value)).isSameAs(value);
	}

	@Test
	void mapping_null_failsAsPythonCallsGetOnNone() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonMappings.mapping(json("null")))
			.withMessage("'NoneType' object has no attribute 'get'");
	}

	@Test
	void mapping_emptyList_failsThoughItCountsAsFalse() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonMappings.mapping(json("[]")))
			.withMessage("'list' object has no attribute 'get'");
	}

	@Test
	void getMapping_missingKey_isAnEmptyMapping() {
		assertThat(PythonMappings.getMapping((ObjectNode) json("{}"), "metadata")).isEmpty();
	}

	@Test
	void getMapping_keyWrittenAsNull_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class)
			.isThrownBy(() -> PythonMappings.getMapping((ObjectNode) json("{\"metadata\": null}"), "metadata"))
			.withMessage("'NoneType' object has no attribute 'get'");
	}

	@Test
	void dictOf_mapping_isACopy() {
		ObjectNode value = (ObjectNode) json("{\"a\": 1}");

		ObjectNode copy = PythonMappings.dictOf(value);
		copy.put("b", 2);

		assertThat(value).isEqualTo(json("{\"a\": 1}"));
	}

	@Test
	void dictOf_pairs_areTheirKeysAndValuesInOrder() {
		ObjectNode mapping = PythonMappings
			.dictOf(json("[[\"b\", 1], \"xy\", {\"k\": 1, \"v\": 2}, [3, null], [\"b\", 4]]"));

		assertThat(mapping.propertyNames()).containsExactly("b", "x", "k", "3");
		assertThat(mapping).isEqualTo(json("{\"b\": 4, \"x\": \"y\", \"k\": \"v\", \"3\": null}"));
	}

	@Test
	void dictOf_valueThatCountsAsFalse_isAnEmptyMapping() {
		assertThat(PythonMappings.dictOf(json("null"))).isEmpty();
		assertThat(PythonMappings.dictOf(json("\"\""))).isEmpty();
	}

	@Test
	void dictOf_pairOfAnotherLength_isRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> PythonMappings.dictOf(json("[[\"a\", 1], [\"b\"]]")))
			.withMessage("dictionary update sequence element #1 has length 1; 2 is required");
	}

	@Test
	void dictOf_text_isRefusedAsPairsOfOneCharacter() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> PythonMappings.dictOf(json("\"ab\"")))
			.withMessage("dictionary update sequence element #0 has length 1; 2 is required");
	}

	@Test
	void dictOf_itemThatIsNoSequence_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonMappings.dictOf(json("[null]")))
			.withMessage("cannot convert dictionary update sequence element #0 to a sequence");
	}

	@Test
	void dictOf_keyThatIsAList_failsAsUnhashable() {
		assertThatExceptionOfType(ImportShapeException.class)
			.isThrownBy(() -> PythonMappings.dictOf(json("[[[1], 2]]")))
			.withMessage("unhashable type: 'list'");
	}

	@Test
	void dictOf_number_failsAsNotIterable() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonMappings.dictOf(json("5")))
			.withMessage("'int' object is not iterable");
	}

	@Test
	void texts_ofAMapping_areItsKeysAndValuesAsPythonWritesThem() {
		assertThat(PythonMappings.texts(json("{\"a\": true, \"b\": null, \"c\": 1.0}")))
			.containsExactly(entry("a", "True"), entry("b", "None"), entry("c", "1.0"));
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
