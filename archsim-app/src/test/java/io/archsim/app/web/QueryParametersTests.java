package io.archsim.app.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Query parameters read as FastAPI read them.
 */
class QueryParametersTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void text_repeated_isTheLastValue() {
		assertThat(QueryParameters.text(List.of("first", "last"), "next", 10)).contains("last");
	}

	@Test
	void text_notGiven_isEmpty() {
		assertThat(QueryParameters.text(null, "next", 10)).isEmpty();
	}

	@Test
	void text_charactersOutsideTheBasicPlane_countOnceEach() {
		assertThat(QueryParameters.text(List.of("😀".repeat(3)), "next", 3)).isPresent();
	}

	@Test
	void text_longer_isRefusedAsPydanticRefusesIt() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.text(List.of("abcd"), "next", 3))
			.satisfies((ex) -> assertThat(ex.detail().get(0).path("type").asString()).isEqualTo("string_too_long"));
	}

	@Test
	void required_given_areTheLastValuesInOrder() {
		SequencedMap<String, @Nullable List<String>> query = new LinkedHashMap<>();
		query.put("a", List.of("x", "first"));
		query.put("b", List.of("second"));

		assertThat(QueryParameters.required(query)).containsExactly("first", "second");
	}

	@Test
	void required_missing_isRefusedNamingEveryOne() {
		SequencedMap<String, @Nullable List<String>> query = new LinkedHashMap<>();
		query.put("a", null);
		query.put("b", List.of());

		assertThatExceptionOfType(RequestInvalidException.class).isThrownBy(() -> QueryParameters.required(query))
			.satisfies((ex) -> assertThat(ex.detail().valueStream().map((error) -> error.path("loc").get(1).asString()))
				.containsExactly("a", "b"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "on", "T", "True", "y", "YES" })
	void flag_pydanticsWordsForTrue_isTrue(String word) {
		assertThat(QueryParameters.flag(List.of(word), "everywhere")).contains(true);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "OFF", "f", "false", "N", "no" })
	void flag_pydanticsWordsForFalse_isFalse(String word) {
		assertThat(QueryParameters.flag(List.of(word), "everywhere")).contains(false);
	}

	@Test
	void flag_notGiven_isEmpty() {
		assertThat(QueryParameters.flag(null, "everywhere")).isEmpty();
	}

	@Test
	void flag_otherWord_isRefusedAsPydanticRefusesIt() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.flag(List.of("maybe"), "everywhere"))
			.satisfies((ex) -> assertThat(ex.detail().get(0).path("type").asString()).isEqualTo("bool_parsing"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "5", " 5 ", "+5", "05", "5.00", "\u00a05\u2003" })
	void integer_writtenAsPydanticReadsFive_isFive(String text) {
		assertThat(QueryParameters.integer(List.of(text), "limit", 1, 200)).hasValue(5);
	}

	@Test
	void integer_withUnderscores_isTheNumber() {
		assertThat(QueryParameters.integer(List.of("1_0"), "limit", 1, 200)).hasValue(10);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "abc", "5.5", "5.", "1__0", "1e2", "0x10", "٥", "5 0", "\u001c5" })
	void integer_notAWholeNumber_isRefusedAsPydanticRefusesIt(String text) {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.integer(List.of(text), "limit", 1, 200))
			.satisfies((ex) -> assertThat(ex.detail().get(0).path("type").asString()).isEqualTo("int_parsing"));
	}

	@Test
	void integer_belowTheLeast_isRefusedWithTheBound() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.integer(List.of("0"), "limit", 1, 200))
			.satisfies((ex) -> assertThat(ex.detail().get(0)).hasToString(compact("""
					{"type": "greater_than_equal", "loc": ["query", "limit"],
					 "msg": "Input should be greater than or equal to 1", "input": "0", "ctx": {"ge": 1}}""")));
	}

	@Test
	void integer_aboveTheMost_isRefusedWithTheBound() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.integer(List.of("201"), "limit", 1, 200))
			.satisfies((ex) -> assertThat(ex.detail().get(0)).hasToString(compact("""
					{"type": "less_than_equal", "loc": ["query", "limit"],
					 "msg": "Input should be less than or equal to 200", "input": "201", "ctx": {"le": 200}}""")));
	}

	@Test
	void integer_pastALongWithNoUpperBound_isTheLargestLong() {
		assertThat(QueryParameters.integer(List.of("1" + "0".repeat(25)), "before", 1)).hasValue(Long.MAX_VALUE);
	}

	@Test
	void integer_notGiven_isEmpty() {
		assertThat(QueryParameters.integer(null, "before", 1)).isEmpty();
	}

	@Test
	void choice_oneOfTheWords_isIt() {
		assertThat(QueryParameters.choice(List.of("edge"), "anchorKind", List.of("node", "edge"))).contains("edge");
	}

	@Test
	void choice_otherWord_isRefusedAsPydanticRefusesIt() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.choice(List.of("x"), "anchorKind", List.of("node", "edge", "run")))
			.satisfies((ex) -> assertThat(ex.detail().get(0)).hasToString(compact("""
					{"type": "literal_error", "loc": ["query", "anchorKind"],
					 "msg": "Input should be 'node', 'edge' or 'run'", "input": "x",
					 "ctx": {"expected": "'node', 'edge' or 'run'"}}""")));
	}

	/**
	 * JSON as Jackson writes it, without spaces: a number is a number, whichever Java
	 * type holds it.
	 * @param text the JSON
	 * @return the same JSON, compact
	 */
	private static String compact(String text) {
		return JSON.readTree(text).toString();
	}

}
