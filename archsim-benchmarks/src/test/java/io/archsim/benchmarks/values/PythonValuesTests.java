package io.archsim.benchmarks.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class PythonValuesTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void median_ofAnEvenCount_isTheMeanOfTheMiddleTwo() {
		List<JsonNode> values = List.of(NODES.numberNode(4.0), NODES.numberNode(1.0), NODES.numberNode(3.0),
				NODES.numberNode(2.0));

		JsonNode median = PythonValues.median(values);

		assertThat(median).isEqualTo(NODES.numberNode(2.5));
	}

	@Test
	void median_withNone_leavesItOut() {
		List<JsonNode> values = List.of(NODES.numberNode(3.0), NullNode.instance, NODES.numberNode(1.0));

		JsonNode median = PythonValues.median(values);

		assertThat(median).isEqualTo(NODES.numberNode(2.0));
	}

	@Test
	void median_ofNothing_isNone() {
		JsonNode median = PythonValues.median(List.of());

		assertThat(median.isNull()).isTrue();
	}

	@Test
	void median_mostlyInfinite_isNone() {
		List<JsonNode> values = List.of(NODES.numberNode(1.0), NODES.numberNode(Double.POSITIVE_INFINITY),
				NODES.numberNode(Double.POSITIVE_INFINITY));

		JsonNode median = PythonValues.median(values);

		assertThat(median.isNull()).isTrue();
	}

	@Test
	void median_ofAnOddCountOfIntegers_isTheMiddleInteger() {
		List<JsonNode> values = List.of(NODES.numberNode(3), NODES.numberNode(1), NODES.numberNode(2));

		JsonNode median = PythonValues.median(values);

		assertThat(median.isIntegralNumber()).isTrue();
		assertThat(median.intValue()).isEqualTo(2);
	}

	@Test
	void median_ofAnEvenCountOfIntegers_isAFloat() {
		List<JsonNode> values = List.of(NODES.numberNode(3), NODES.numberNode(1));

		JsonNode median = PythonValues.median(values);

		assertThat(median).isEqualTo(NODES.numberNode(2.0));
	}

	@Test
	void round_ofAnInteger_staysAnInteger() {
		JsonNode rounded = PythonValues.round(NODES.numberNode(100), 1);

		assertThat(rounded).isEqualTo(NODES.numberNode(100));
	}

	@Test
	void round_ofAFloat_roundsItsExactValueHalfToEven() {
		JsonNode rounded = PythonValues.round(NODES.numberNode(2.675), 2);

		assertThat(rounded).isEqualTo(NODES.numberNode(2.67));
	}

	@Test
	void subtract_ofIntegers_isAnInteger() {
		JsonNode difference = PythonValues.subtract(NODES.numberNode(300), NODES.numberNode(200));

		assertThat(difference).isEqualTo(NODES.numberNode(100));
	}

	@Test
	void subtract_withAFloat_isAFloat() {
		JsonNode difference = PythonValues.subtract(NODES.numberNode(300), NODES.numberNode(200.0));

		assertThat(difference).isEqualTo(NODES.numberNode(100.0));
	}

	@Test
	void divide_ofIntegers_isAFloat() {
		JsonNode quotient = PythonValues.divide(NODES.numberNode(1), NODES.numberNode(4));

		assertThat(quotient).isEqualTo(NODES.numberNode(0.25));
	}

	@Test
	void max_ofEqualValues_isTheFirst() {
		JsonNode largest = PythonValues.max(List.of(NODES.numberNode(2), NODES.numberNode(2.0)));

		assertThat(largest).isEqualTo(NODES.numberNode(2));
	}

	@Test
	void min_ofEqualValues_isTheFirst() {
		JsonNode smallest = PythonValues.min(List.of(NODES.numberNode(2.0), NODES.numberNode(2)));

		assertThat(smallest).isEqualTo(NODES.numberNode(2.0));
	}

	@Test
	void add_ofIntegers_isAnInteger() {
		JsonNode sum = PythonValues.add(NODES.numberNode(2), NODES.numberNode(3));

		assertThat(sum).isEqualTo(NODES.numberNode(5));
	}

	@Test
	void multiply_ofIntegers_isAnInteger() {
		JsonNode product = PythonValues.multiply(NODES.numberNode(4), NODES.numberNode(25));

		assertThat(product).isEqualTo(NODES.numberNode(100));
	}

	@Test
	void compare_anIntegerAndTheEqualFloat_isEqual() {
		int order = PythonValues.compare(NODES.numberNode(2), NODES.numberNode(2.0));

		assertThat(order).isZero();
	}

	@Test
	void compare_zeroAndNegativeZero_isEqual() {
		int order = PythonValues.compare(NODES.numberNode(0.0), NODES.numberNode(-0.0));

		assertThat(order).isZero();
	}

	@Test
	void abs_ofAnInteger_isAnInteger() {
		JsonNode absolute = PythonValues.abs(NODES.numberNode(-3));

		assertThat(absolute).isEqualTo(NODES.numberNode(3));
	}

	// the values CPython 3.12.13 gives
	@Test
	void sum_ofTwelveEighties_compensatesItsRounding() {
		List<JsonNode> values = Collections.nCopies(12, NODES.numberNode(0.8));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(9.600000000000001));
	}

	@Test
	void sum_ofTenTenths_isOne() {
		List<JsonNode> values = Collections.nCopies(10, NODES.numberNode(0.1));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(1.0));
	}

	@Test
	void sum_ofIntegers_isAnInteger() {
		JsonNode sum = PythonValues.sum(List.of(NODES.numberNode(3), NODES.numberNode(4)));

		assertThat(sum).isEqualTo(NODES.numberNode(7));
	}

	@Test
	void sum_ofNothing_isTheIntegerZero() {
		JsonNode sum = PythonValues.sum(List.of());

		assertThat(sum).isEqualTo(NODES.numberNode(0));
	}

	@Test
	void sum_ofIntegersThenFloats_addsTheIntegersExactlyFirst() {
		List<JsonNode> values = new ArrayList<>(List.of(NODES.numberNode(1), NODES.numberNode(2)));
		values.addAll(Collections.nCopies(10, NODES.numberNode(0.1)));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(4.0));
	}

	// CPython 3.12 adds an integer after a float without compensating its rounding
	@Test
	void sum_ofIntegersAfterAFloat_losesTheirRounding() {
		List<JsonNode> values = List.of(NODES.numberNode(1e16), NODES.numberNode(1), NODES.numberNode(1),
				NODES.numberNode(1), NODES.numberNode(1));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(1e16));
	}

	@Test
	void sum_ofFloatsAfterAFloat_keepsTheirRounding() {
		List<JsonNode> values = List.of(NODES.numberNode(1e16), NODES.numberNode(1.0), NODES.numberNode(1.0),
				NODES.numberNode(1.0), NODES.numberNode(1.0));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(1.0000000000000004e16));
	}

	@Test
	void sum_thatOverflows_isInfinite() {
		List<JsonNode> values = List.of(NODES.numberNode(1e308), NODES.numberNode(1e308), NODES.numberNode(-1e308));

		JsonNode sum = PythonValues.sum(values);

		assertThat(sum).isEqualTo(NODES.numberNode(Double.POSITIVE_INFINITY));
	}

	@Test
	void orNone_aValue_isTheValue() {
		JsonNode value = PythonValues.orNone(Optional.of(NODES.numberNode(3)));

		assertThat(value).isEqualTo(NODES.numberNode(3));
	}

	@Test
	void orNone_noValue_isNone() {
		JsonNode value = PythonValues.orNone(Optional.empty());

		assertThat(value.isNull()).isTrue();
	}

	@Test
	void equal_anIntegerAndTheEqualFloat_isEqual() {
		assertThat(PythonValues.equal(NODES.numberNode(2), NODES.numberNode(2.0))).isTrue();
	}

	@Test
	void equal_objectsWithTheSameFieldsInAnotherOrder_isEqual() {
		JsonNode left = JsonText.read("{\"a\": 1, \"b\": [2.0, null]}");
		JsonNode right = JsonText.read("{\"b\": [2, null], \"a\": 1.0}");

		assertThat(PythonValues.equal(left, right)).isTrue();
	}

	@Test
	void equal_noneAndANumber_isNotEqual() {
		assertThat(PythonValues.equal(NullNode.instance, NODES.numberNode(0))).isFalse();
	}

	@Test
	void equal_arraysOfDifferentLengths_isNotEqual() {
		assertThat(PythonValues.equal(JsonText.read("[1, 2]"), JsonText.read("[1, 2, 3]"))).isFalse();
	}

	// unlike Python, where True == 1
	@Test
	void equal_aBooleanAndOne_isNotEqual() {
		assertThat(PythonValues.equal(NODES.booleanNode(true), NODES.numberNode(1))).isFalse();
	}

	@Test
	void equal_noneAndNone_isEqual() {
		assertThat(PythonValues.equal(NullNode.instance, NullNode.instance)).isTrue();
	}

	@Test
	void equal_notANumberAndANumber_isNotEqual() {
		assertThat(PythonValues.equal(NODES.numberNode(Double.NaN), NODES.numberNode(5.0))).isFalse();
	}

	@Test
	void equal_integersBeyondTheDoublesPrecision_areComparedExactly() {
		assertThat(PythonValues.equal(NODES.numberNode(9007199254740993L), NODES.numberNode(9007199254740992L)))
			.isFalse();
	}

	// where Python finds the one NaN object json reads equal to itself
	@Test
	void equal_notANumberInTwoArrays_isNotEqual() {
		JsonNode left = NODES.arrayNode().add(Double.NaN);
		JsonNode right = NODES.arrayNode().add(Double.NaN);

		assertThat(PythonValues.equal(left, right)).isFalse();
	}

}
