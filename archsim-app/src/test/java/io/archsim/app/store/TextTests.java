package io.archsim.app.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text counted and cut in characters, as Python does.
 */
class TextTests {

	private static final String SMILE = "😀";

	@Test
	void length_characterOutsideTheBasicPlane_isOne() {
		assertThat(Text.length(SMILE)).isEqualTo(1);
	}

	@Test
	void cut_longerText_keepsWholeCharacters() {
		assertThat(Text.cut(SMILE.repeat(3), 2)).isEqualTo(SMILE.repeat(2));
	}

	@Test
	void cut_shorterText_isTheText() {
		assertThat(Text.cut("abc", 5)).isEqualTo("abc");
	}

}
