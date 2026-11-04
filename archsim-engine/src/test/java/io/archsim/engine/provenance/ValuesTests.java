package io.archsim.engine.provenance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparing JSON values as the Python engine does; each case is what Python 3.12 gives.
 */
class ValuesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void equal_aSwitchAndOne_areEqualAsInPython() {
		assertThat(Values.equal(json("true"), json("1"))).isTrue();
	}

	@Test
	void equal_distributionsWrittenWithWholeAndDecimalNumbers_areEqual() {
		assertThat(Values.equal(json("{\"kind\": \"lognormal\", \"median\": 15, \"p95\": 40}"),
				json("{\"kind\": \"lognormal\", \"median\": 15.0, \"p95\": 40.0}")))
			.isTrue();
	}

	@Test
	void equal_listsItemByItem_compareNumbersByValue() {
		assertThat(Values.equal(json("[1, true]"), json("[1.0, 1]"))).isTrue();
	}

	@Test
	void equal_noneAndNone_areEqual() {
		assertThat(Values.equal(json("null"), json("null"))).isTrue();
	}

	@Test
	void equal_noneAndZero_differ() {
		assertThat(Values.equal(json("null"), json("0"))).isFalse();
	}

	@Test
	void equal_aTextAndANumber_differ() {
		assertThat(Values.equal(json("\"1\""), json("1"))).isFalse();
	}

	@Test
	void same_aSwitchAndOne_differ() {
		assertThat(Values.same(json("true"), json("1"))).isFalse();
	}

	@Test
	void same_aSwitchAndItself_isTheSame() {
		assertThat(Values.same(json("true"), json("true"))).isTrue();
	}

	@Test
	void same_numbersApartByFloatNoise_areTheSame() {
		assertThat(Values.same(json("100"), json("100.0000000001"))).isTrue();
	}

	@Test
	void same_aTinyNumberAndZero_areTheSameByTheAbsoluteTolerance() {
		assertThat(Values.same(json("1e-13"), json("0"))).isTrue();
	}

	@Test
	void same_numbersApartByMoreThanTheTolerance_differ() {
		assertThat(Values.same(json("1.0"), json("1.001"))).isFalse();
	}

	@Test
	void same_objectsWithWholeAndDecimalNumbers_areTheSame() {
		assertThat(Values.same(json("{\"a\": 1}"), json("{\"a\": 1.0}"))).isTrue();
	}

	@Test
	void same_objectsWithDifferentKeys_differ() {
		assertThat(Values.same(json("{\"a\": 1}"), json("{\"b\": 1}"))).isFalse();
	}

	@Test
	void same_listsOfDifferentLengths_differ() {
		assertThat(Values.same(json("[1]"), json("[1, 2]"))).isFalse();
	}

	@Test
	void same_noneAndNone_areTheSame() {
		assertThat(Values.same(json("null"), json("null"))).isTrue();
	}

	@Test
	void same_noneAndZero_differ() {
		assertThat(Values.same(json("null"), json("0"))).isFalse();
	}

	@Test
	void same_equalTexts_areTheSame() {
		assertThat(Values.same(json("\"x\""), json("\"x\""))).isTrue();
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
