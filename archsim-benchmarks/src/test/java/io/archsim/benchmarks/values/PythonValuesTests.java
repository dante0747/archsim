package io.archsim.benchmarks.values;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

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

}
