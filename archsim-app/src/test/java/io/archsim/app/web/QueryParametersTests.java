package io.archsim.app.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Query parameters read as FastAPI read them.
 */
class QueryParametersTests {

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

	@ParameterizedTest
	@ValueSource(strings = { "1", "on", "T", "True", "y", "YES" })
	void flag_pydanticsWordsForTrue_isTrue(String word) {
		assertThat(QueryParameters.flag(List.of(word), "everywhere")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "OFF", "f", "false", "N", "no" })
	void flag_pydanticsWordsForFalse_isFalse(String word) {
		assertThat(QueryParameters.flag(List.of(word), "everywhere")).isFalse();
	}

	@Test
	void flag_notGiven_isFalse() {
		assertThat(QueryParameters.flag(null, "everywhere")).isFalse();
	}

	@Test
	void flag_otherWord_isRefusedAsPydanticRefusesIt() {
		assertThatExceptionOfType(RequestInvalidException.class)
			.isThrownBy(() -> QueryParameters.flag(List.of("maybe"), "everywhere"))
			.satisfies((ex) -> assertThat(ex.detail().get(0).path("type").asString()).isEqualTo("bool_parsing"));
	}

}
