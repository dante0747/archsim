package io.archsim.engine.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Texts ordered as Python 3.12 orders them. */
class PythonStringsTests {

	private static final int HEX = 16;

	@Test
	void casefold_upperAndLowerCase_foldTogether() {
		assertThat(PythonStrings.casefold("API Servers")).isEqualTo("api servers");
	}

	@Test
	void casefold_aSharpS_isTwoLetters() {
		assertThat(PythonStrings.casefold("Straße")).isEqualTo("strasse");
		assertThat(PythonStrings.casefold("\u1E9E")).isEqualTo("ss");
	}

	@Test
	void casefold_aFinalSigma_isAPlainSigma() {
		assertThat(PythonStrings.casefold("ΣΑΣ")).isEqualTo("σασ");
	}

	@Test
	void casefold_aDotlessI_staysDotless() {
		assertThat(PythonStrings.casefold("\u0131")).isEqualTo("\u0131");
	}

	@Test
	void casefold_cherokee_foldsToItsCapitals() {
		assertThat(PythonStrings.casefold("\u13A0\uAB70\u13F8\u13F0")).isEqualTo("\u13A0\u13A0\u13F0\u13F0");
	}

	@Test
	void casefold_aLetterNewerThanUnicode15_foldsToItself() {
		// Unicode 16 gave U+A7CB a lower case, which a newer JDK knows and Python 3.12
		// does not
		assertThat(PythonStrings.casefold("\uA7CB api")).isEqualTo("\uA7CB api");
		assertThat(PythonStrings.compare(PythonStrings.casefold("Ω api"), PythonStrings.casefold("\uA7CB api")))
			.isNegative();
	}

	@TestFactory
	Stream<DynamicTest> casefold_eachCodePointOfTheTable_foldsAsListed() throws IOException {
		return table().filter((line) -> !line.startsWith("#")).map((line) -> {
			String[] columns = line.split("\t", -1);
			String folded = Stream.of(columns[1].split(" ", -1))
				.map((hex) -> Character.toString(Integer.parseInt(hex, HEX)))
				.reduce("", String::concat);
			return DynamicTest.dynamicTest(columns[0],
					() -> assertThat(PythonStrings.casefold(Character.toString(Integer.parseInt(columns[0], HEX))))
						.isEqualTo(folded));
		});
	}

	@Test
	void compare_aCharacterBeyondTheBasicPlane_comesAfterTheBasicPlanesLast() {
		// in UTF-16 units, U+1F600 starts with a surrogate below U+FF01
		assertThat(PythonStrings.compare("\uFF01", "\uD83D\uDE00")).isNegative();
		assertThat(PythonStrings.compare("\uD83D\uDE00", "\uFF01")).isPositive();
	}

	@Test
	void compare_aPrefix_comesFirst() {
		assertThat(PythonStrings.compare("ab", "abc")).isNegative();
		assertThat(PythonStrings.compare("abc", "abc")).isZero();
	}

	private static Stream<String> table() throws IOException {
		try (InputStream in = Objects.requireNonNull(PythonStringsTests.class.getResourceAsStream("casefold.tsv"),
				"casefold.tsv")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList().stream();
		}
	}

}
