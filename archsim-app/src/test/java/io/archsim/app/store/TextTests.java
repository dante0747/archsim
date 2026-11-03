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

	@Test
	void strip_noBreakAndNextLineSpaces_areTrimmedAsPythonTrimsThem() {
		assertThat(Text.strip("\u00a0\u0085 note \u202f\u3000")).isEqualTo("note");
	}

	@Test
	void strip_onlySpaces_isEmpty() {
		assertThat(Text.strip(" \t\n\u2007")).isEmpty();
	}

	@Test
	void strip_spacesInside_stay() {
		assertThat(Text.strip(" a  b ")).isEqualTo("a  b");
	}

	@Test
	void oneLine_wordsAcrossLines_areOneSpaceApart() {
		assertThat(Text.oneLine("\n Looks\tgood,\r\n\u00a0ship   it \n")).isEqualTo("Looks good, ship it");
	}

	@Test
	void oneLine_onlySpaces_isEmpty() {
		assertThat(Text.oneLine(" \n\t ")).isEmpty();
	}

}
