package io.archsim.engine.compare;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python's {@code ==} on JSON values: numbers by value whatever their kind, lists item by
 * item, objects key by key in any order, and {@code None} equal only to {@code None}.
 */
class PythonValuesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void equal_anIntegerAndTheSameFloat_areEqual() {
		assertThat(PythonValues.equal(json("2"), json("2.0"))).isTrue();
	}

	@Test
	void equal_trueAndOne_areEqual() {
		assertThat(PythonValues.equal(json("true"), json("1"))).isTrue();
	}

	@Test
	void equal_listsOfOtherLengths_areNotEqual() {
		assertThat(PythonValues.equal(json("[[0, 100], [10, 200]]"), json("[[0, 100]]"))).isFalse();
	}

	@Test
	void equal_objectsWithTheirKeysInAnotherOrder_areEqual() {
		assertThat(PythonValues.equal(json("{\"a\": 1, \"b\": 2}"), json("{\"b\": 2, \"a\": 1.0}"))).isTrue();
	}

	@Test
	void equal_objectsWithAnotherKey_areNotEqual() {
		assertThat(PythonValues.equal(json("{\"a\": 1}"), json("{\"b\": 1}"))).isFalse();
	}

	@Test
	void equal_aStringAndANumber_areNotEqual() {
		assertThat(PythonValues.equal(json("\"3\""), json("3"))).isFalse();
	}

	@Test
	void equal_aMissingValueAndNull_areEqual() {
		assertThat(PythonValues.equal(MissingNode.getInstance(), json("null"))).isTrue();
	}

	@Test
	void equal_nullAndZero_areNotEqual() {
		assertThat(PythonValues.equal(json("null"), json("0"))).isFalse();
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
