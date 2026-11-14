package io.archsim.tools.commits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPython's {@code str} as the check quotes, splits and trims messages: {@code repr()},
 * {@code splitlines()}, and whitespace as {@code str.isspace()} has it, which is wider
 * than Java's.
 */
class PythonStrTests {

	@Test
	void repr_aText_isInSingleQuotes() {
		assertThat(PythonStr.repr("Add a thing")).isEqualTo("'Add a thing'");
	}

	@Test
	void repr_aTextWithASingleQuote_isInDoubleQuotes() {
		assertThat(PythonStr.repr("Merge 'w23'")).isEqualTo("\"Merge 'w23'\"");
	}

	@Test
	void repr_aTextWithBothQuotes_escapesTheSingleOne() {
		assertThat(PythonStr.repr("it's \"so\"")).isEqualTo("'it\\'s \"so\"'");
	}

	@Test
	void repr_aTabABackslashAndAControl_escapesThem() {
		assertThat(PythonStr.repr("a\tb\\c\u0007")).isEqualTo("'a\\tb\\\\c\\x07'");
	}

	// printable outside ASCII stays; a no-break space and a line separator do not
	@Test
	void repr_textOutsideAscii_keepsWhatPythonPrints() {
		assertThat(PythonStr.repr("\u00e9\u00a0\u2028")).isEqualTo("'\u00e9\\xa0\\u2028'");
	}

	@Test
	void splitlines_unicodesLineBreaks_splitTheText() {
		assertThat(PythonStr.splitlines("a\r\nb\u2028c\u0085d\n")).containsExactly("a", "b", "c", "d");
	}

	@Test
	void splitlines_blankLines_stay() {
		assertThat(PythonStr.splitlines("a\n\nb")).containsExactly("a", "", "b");
	}

	// no-break, figure and narrow no-break spaces, the next line, the information
	// separators
	@ParameterizedTest
	@ValueSource(strings = { "\u00a0", "\u2007", "\u202f", "\u0085", "\u001c", "\u001f", "\u3000", " \t" })
	void isBlank_pythonsWhitespace_isBlank(String text) {
		assertThat(PythonStr.isBlank(text)).isTrue();
	}

	// a zero-width space is a format character to Python, not whitespace
	@Test
	void isBlank_aZeroWidthSpace_isNot() {
		assertThat(PythonStr.isBlank("\u200b")).isFalse();
	}

	@Test
	void rstrip_trailingNoBreakSpaces_areGone() {
		assertThat(PythonStr.rstrip(" feat \u00a0\u2007")).isEqualTo(" feat");
	}

	@Test
	void strip_pythonsWhitespaceAround_isGone() {
		assertThat(PythonStr.strip("\u202f\u001c co-authored-by \u00a0")).isEqualTo("co-authored-by");
	}

}
