package io.archsim.model.text;

import java.math.BigInteger;
import java.util.HexFormat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python's text for values, as {@code repr()}, {@code str()} and {@code str.title()}
 * write it (Python 3.12).
 */
class PythonTextTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void capitalize_aWordInCapitals_keepsItsFirstLetterOnly() {
		assertThat(PythonText.capitalize("HIGH")).isEqualTo("High");
	}

	@Test
	void capitalize_aWordStartingWithALigature_writesItsTitleCase() {
		// U+01C6 (dž) title-cases to U+01C5 (Dž), as Python's str.capitalize() writes it
		assertThat(PythonText.capitalize("ǆungla")).isEqualTo("ǅungla");
	}

	@Test
	void truthy_wholeNumberBeyondADouble_isTrue() {
		assertThat(PythonText.truthy(JSON.readTree("1" + "0".repeat(400)))).isTrue();
	}

	@Test
	void truthy_wholeNumberZero_isFalse() {
		assertThat(PythonText.truthy(JsonNodeFactory.instance.numberNode(BigInteger.ZERO))).isFalse();
	}

	@ParameterizedTest(name = "repr({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			0.0                     | 0.0
			-0.0                    | -0.0
			0.25                    | 0.25
			100                     | 100.0
			0.0001                  | 0.0001
			0.00001                 | 1e-05
			1e15                    | 1000000000000000.0
			1e16                    | 1e+16
			1e22                    | 1e+22
			123456789               | 123456789.0
			2.675                   | 2.675
			-12.345                 | -12.345
			0.3333333333333333      | 0.3333333333333333
			1e-7                    | 1e-07
			1.7976931348623157e308  | 1.7976931348623157e+308
			0.30000000000000004     | 0.30000000000000004
			12345.678               | 12345.678
			""")
	void repr_float_isWhatPythonWrites(double value, String python) {
		assertThat(PythonText.repr(value)).isEqualTo(python);
	}

	@Property
	void repr_finiteFloat_readsBackAsTheSameFloat(@ForAll double value) {
		if (Double.isFinite(value)) {
			assertThat(Double.parseDouble(PythonText.repr(value))).isEqualTo(value);
		}
	}

	@Test
	void repr_specialFloats_areInfAndNan() {
		assertThat(PythonText.repr(Double.POSITIVE_INFINITY)).isEqualTo("inf");
		assertThat(PythonText.repr(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
		assertThat(PythonText.repr(Double.NaN)).isEqualTo("nan");
	}

	@ParameterizedTest(name = "str({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			null                 | None
			true                 | True
			5                    | 5
			1.5                  | 1.5
			"text"               | text
			["a", 1, null]       | ['a', 1, None]
			{"a": {"b": false}}  | {'a': {'b': False}}
			""")
	void str_jsonValue_isWhatPythonWritesForJsonLoads(String json, String python) {
		assertThat(PythonText.str(JSON.readTree(json))).isEqualTo(python);
	}

	@ParameterizedTest(name = "repr of {0}")
	@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			plain  | 'plain'
			it's   | "it's"
			a"b    | 'a"b'
			""")
	void repr_string_isQuotedAsPythonQuotesIt(String text, String python) {
		assertThat(PythonText.repr(text)).isEqualTo(python);
	}

	@Test
	void repr_controlCharacter_isEscaped() {
		assertThat(PythonText.repr("tab\there")).isEqualTo("'tab\\there'");
	}

	@ParameterizedTest(name = "{2} (U+{0}) is escaped as {1}", quoteTextArguments = false)
	@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			0000   | 'a\\x00b'       | null, a C0 control character
			007F   | 'a\\x7fb'       | delete, a control character
			0085   | 'a\\x85b'       | next line, a control character
			00A0   | 'a\\xa0b'       | no-break space, a space separator
			00AD   | 'a\\xadb'       | soft hyphen, a format character
			0378   | 'a\\u0378b'     | unassigned
			200B   | 'a\\u200bb'     | zero width space, a format character
			2028   | 'a\\u2028b'     | line separator
			2029   | 'a\\u2029b'     | paragraph separator
			3000   | 'a\\u3000b'     | ideographic space, a space separator
			D800   | 'a\\ud800b'     | a surrogate on its own
			E000   | 'a\\ue000b'     | private use
			FEFF   | 'a\\ufeffb'     | byte order mark, a format character
			FFFF   | 'a\\uffffb'     | a noncharacter, the last escaped in four hex digits
			1C89   | 'a\\u1c89b'     | a letter since Unicode 16.0, unassigned in Python's 15.0
			E0001  | 'a\\U000e0001b' | language tag, a format character
			10FFFF | 'a\\U0010ffffb' | the last noncharacter, unassigned
			""")
	void repr_characterPythonDoesNotPrint_isEscaped(String hex, String python, String kind) {
		String text = "a" + Character.toString(HexFormat.fromHexDigits(hex)) + "b";

		assertThat(PythonText.repr(text)).as(kind).isEqualTo(python);
	}

	@ParameterizedTest(name = "U+{0} is kept as it is", quoteTextArguments = false)
	@ValueSource(strings = { "00E9", "00A1", "0301", "0627", "4E2D", "1F600", "10000" })
	void repr_characterPythonPrints_isKeptAsItIs(String hex) {
		String text = Character.toString(HexFormat.fromHexDigits(hex));

		assertThat(PythonText.repr(text)).isEqualTo("'" + text + "'");
	}

	@ParameterizedTest(name = "{0}.title() is {1}")
	@CsvSource({ "latencyP95Ms, Latencyp95Ms", "snapshotSha256, Snapshotsha256", "autoSize, Autosize", "p95, P95",
			"x, X" })
	void title_camelCaseName_capitalizesEachRunOfLetters(String text, String title) {
		assertThat(PythonText.title(text)).isEqualTo(title);
	}

}
