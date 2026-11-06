package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@code terraform show -json} output read as Python's {@code json.loads} reads it.
 */
class TerraformJsonTests {

	private static final String NOT_JSON = "not JSON: run `terraform show -json plan.out` (or state) first (";

	@Test
	void read_nonNumericNumbers_areFloats() {
		JsonNode read = TerraformJson.read("[NaN, Infinity, -Infinity]");

		assertThat(read.get(0).doubleValue()).isNaN();
		assertThat(read.get(1).doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(read.get(2).doubleValue()).isEqualTo(Double.NEGATIVE_INFINITY);
	}

	@Test
	void read_infinityWithASign_isReadThoughJsonRefusesIt() {
		JsonNode read = TerraformJson.read("[-INF, +INF, +Infinity]");

		assertThat(read.get(0).doubleValue()).isEqualTo(Double.NEGATIVE_INFINITY);
		assertThat(read.get(1).doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(read.get(2).doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@Test
	void read_infinityWithoutASign_isRefusedAsJsonRefusesIt() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> TerraformJson.read("[INF]"))
			.withMessageStartingWith(NOT_JSON);
	}

	@Test
	void read_wholeNumberOfPythonsMostDigits_isRead() {
		assertThat(TerraformJson.read("-" + "9".repeat(4300)).bigIntegerValue().toString()).hasSize(4301);
	}

	@Test
	void read_wholeNumberOfMoreDigits_isRefused() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> TerraformJson.read("9".repeat(4301)))
			.withMessageStartingWith(NOT_JSON);
	}

	@Test
	void read_floatOfMoreDigits_isRefusedThoughPythonReadsIt() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> TerraformJson.read("0." + "1".repeat(4301)))
			.withMessageStartingWith(NOT_JSON);
	}

	@Test
	void read_keyOfSixtyThousandCharacters_isRead() {
		String key = "k".repeat(60_000);

		assertThat(TerraformJson.read("{\"" + key + "\": 1}").has(key)).isTrue();
	}

	@Test
	void read_keyWrittenTwice_keepsItsFirstPlaceAndItsLastValue() {
		JsonNode read = TerraformJson.read("{\"a\": 1, \"b\": 2, \"a\": 3}");

		assertThat(read.propertyNames()).containsExactly("a", "b");
		assertThat(read.path("a").intValue()).isEqualTo(3);
	}

	@Test
	void read_valueThatIsNotAMapping_isRead() {
		assertThat(TerraformJson.read(" 5 ").intValue()).isEqualTo(5);
	}

	@Test
	void read_textWhereNoValueStarts_isRefusedInJsonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> TerraformJson.read("resource \"aws_s3_bucket\" \"b\" {}"))
			.withMessage(NOT_JSON + "Expecting value: line 1 column 1 (char 0))");
	}

	@Test
	void read_valueAfterTheFirst_isRefusedInTheParsersWords() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> TerraformJson.read("{} x"))
			.withMessageStartingWith(NOT_JSON)
			.withMessageContaining(": line 1 column ");
	}

	@Test
	void read_valueCutShort_isRefusedInTheParsersWords() {
		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> TerraformJson.read("{\"a\": "))
			.withMessageStartingWith(NOT_JSON);
	}

	@Test
	void read_nestingOfAThousandLevels_isRead() {
		assertThat(TerraformJson.read("[".repeat(1000) + "]".repeat(1000)).isArray()).isTrue();
	}

	@Test
	void read_nestingPastAThousandLevels_isRefused() {
		String deep = "[".repeat(1001) + "]".repeat(1001);

		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> TerraformJson.read(deep))
			.withMessage("the text nests too deeply to read as `terraform show -json` output");
	}

}
