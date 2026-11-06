package io.archsim.engine.imports;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Python's ways with the values YAML gives, failing in Python's words where Python fails.
 */
class PythonValuesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void items_ofEachKind_areItemsKeysOrCharacters() {
		assertThat(PythonValues.items(json("[1, \"a\"]"))).containsExactly(json("1"), json("\"a\""));
		assertThat(PythonValues.items(json("{\"k\": 1}"))).containsExactly(json("\"k\""));
		assertThat(PythonValues.items(json("\"ab\""))).containsExactly(json("\"a\""), json("\"b\""));
		assertThat(PythonValues.items(json("0"))).isEmpty();
	}

	@Test
	void items_numberThatCountsAsTrue_failsAsNotIterable() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.items(json("true")))
			.withMessage("'bool' object is not iterable");
	}

	@Test
	void item_missingKey_failsAsAKeyError() {
		assertThatExceptionOfType(ImportShapeException.class)
			.isThrownBy(() -> PythonValues.item(json("{}"), "metadata"))
			.withMessage("missing 'metadata'");
	}

	@Test
	void item_ofAListOrAText_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.item(json("[]"), "name"))
			.withMessage("list indices must be integers or slices, not str");
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.item(json("\"x\""), "name"))
			.withMessage("string indices must be integers, not 'str'");
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.item(json("1"), "name"))
			.withMessage("'int' object is not subscriptable");
	}

	@Test
	void contains_ofEachKind_testsKeysItemsOrParts() {
		assertThat(PythonValues.contains(json("{\"parts.json\": 1}"), "parts.json")).isTrue();
		assertThat(PythonValues.contains(json("[\"a\", 1]"), "a")).isTrue();
		assertThat(PythonValues.contains(json("\"xabc\""), "ab")).isTrue();
		assertThat(PythonValues.contains(json("null"), "a")).isFalse();
	}

	@Test
	void contains_numberThatCountsAsTrue_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.contains(json("2"), "a"))
			.withMessage("argument of type 'int' is not iterable");
	}

	@Test
	void first_ofAListOrAText_isItsFirstItemOrCharacter() {
		assertThat(PythonValues.first(json("[null, 1]")).isNull()).isTrue();
		assertThat(PythonValues.first(json("\"web\""))).isEqualTo(json("\"w\""));
	}

	@Test
	void first_ofAMappingOrANumber_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.first(json("{\"a\": 1}")))
			.withMessage("missing 0");
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.first(json("3")))
			.withMessage("'int' object is not subscriptable");
	}

	@Test
	void toInt_ofEachKind_readsAsIntDoes() {
		assertThat(PythonValues.toInt(json("true"))).isEqualTo(BigInteger.ONE);
		assertThat(PythonValues.toInt(json("-2.9"))).isEqualTo(BigInteger.valueOf(-2));
		assertThat(PythonValues.toInt(json("\" 1_000 \""))).isEqualTo(BigInteger.valueOf(1000));
	}

	@Test
	void toInt_textThatIsNoWholeNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> PythonValues.toInt(json("\"1.5\"")))
			.withMessage("invalid literal for int() with base 10: '1.5'");
	}

	@Test
	void toInt_valueOfAnotherKind_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.toInt(json("[]")))
			.withMessage("int() argument must be a string, a bytes-like object or a real number, not 'list'");
	}

	@Test
	void toFloat_ofABooleanOrText_readsAsFloatDoes() {
		assertThat(PythonValues.toFloat(json("false"))).isZero();
		assertThat(PythonValues.toFloat(json("\"1e3\""))).isEqualTo(1000.0);
	}

	@Test
	void toFloat_valueOfAnotherKind_failsInPythonsWords() {
		assertThatExceptionOfType(ImportShapeException.class).isThrownBy(() -> PythonValues.toFloat(json("null")))
			.withMessage("float() argument must be a string or a real number, not 'NoneType'");
	}

	@Test
	void percent_ofWholeNumbers_roundsAsPythonsTrueDivision() {
		assertThat(PythonValues.percent(json("7"))).isEqualTo(0.07);
		assertThat(PythonValues.percent(json("12345678901234567890123"))).isEqualTo(1.2345678901234568E20);
	}

	@Test
	void divided_boolean_dividesItsWholeNumber() {
		assertThat(PythonValues.divided(json("true"), BigDecimal.valueOf(1024))).isEqualTo(1 / 1024.0);
	}

	@Test
	void divided_float_dividesAsFloats() {
		assertThat(PythonValues.divided(json("256.5"), BigDecimal.valueOf(1024))).isEqualTo(256.5 / 1024);
	}

	@Test
	void divided_wholeNumberWhoseQuotientIsBeyondAFloat_isRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> PythonValues.divided(json("1" + "0".repeat(400)), BigDecimal.valueOf(1024)))
			.withMessage("integer division result too large for a float");
	}

	@Test
	void toFloat_wholeNumberBeyondAFloat_isRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> PythonValues.toFloat(json("1" + "0".repeat(400))))
			.withMessage("int too large to convert to float");
	}

	@Test
	void truthy_wholeNumberBeyondAFloat_isTrue() {
		assertThat(PythonValues.truthy(json("1" + "0".repeat(400)))).isTrue();
	}

	@Test
	void type_ofEachKind_isPythonsName() {
		assertThat(PythonValues.type(json("null"))).isEqualTo("NoneType");
		assertThat(PythonValues.type(json("true"))).isEqualTo("bool");
		assertThat(PythonValues.type(json("1"))).isEqualTo("int");
		assertThat(PythonValues.type(json("1.5"))).isEqualTo("float");
		assertThat(PythonValues.type(json("\"a\""))).isEqualTo("str");
		assertThat(PythonValues.type(json("[]"))).isEqualTo("list");
		assertThat(PythonValues.type(json("{}"))).isEqualTo("dict");
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
