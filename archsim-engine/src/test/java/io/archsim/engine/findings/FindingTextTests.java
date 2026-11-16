package io.archsim.engine.findings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How findings write a count of things: the noun agrees with the count. */
class FindingTextTests {

	@Test
	void count_one_isSingular() {
		String text = FindingText.count(1, "request");

		assertThat(text).isEqualTo("1 request");
	}

	@Test
	void count_many_isPluralWithThousandsSeparators() {
		String text = FindingText.count(1287, "client request");

		assertThat(text).isEqualTo("1,287 client requests");
	}

	@Test
	void count_none_isPlural() {
		String text = FindingText.count(0, "request");

		assertThat(text).isEqualTo("0 requests");
	}

}
