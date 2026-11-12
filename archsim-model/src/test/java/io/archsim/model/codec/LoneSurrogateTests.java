package io.archsim.model.codec;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.SampleRecords.Drawing;
import io.archsim.model.codec.SampleRecords.Part;
import io.archsim.model.codec.SampleRecords.Sample;
import io.archsim.model.codec.SampleRecords.Versioned;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A string holding half of a surrogate pair, which JSON carries as an escape and UTF-8
 * cannot encode, is read as pydantic read it. pydantic encodes a string to UTF-8 to check
 * its length or pattern, to compare it with a literal and to parse a number or a boolean
 * from it, and refuses it there; it encodes each key of a record to find the unknown
 * ones, and refuses the whole record; it writes a union's tag with three replacement
 * characters for each lone surrogate; and it takes a string with nothing to check as it
 * is. Every expected error here is pydantic 2's; the frozen engine's answers for whole
 * designs are {@code LoneSurrogateDocumentsTests}'.
 */
class LoneSurrogateTests {

	private static final String HIGH = jsonEscape(Character.MIN_HIGH_SURROGATE);

	private static final String LOW = jsonEscape(Character.MIN_LOW_SURROGATE);

	/**
	 * A lone surrogate itself: in a key, because Jackson's parser refuses one escaped in
	 * a property name before the codec could read it, and as a value read.
	 */
	private static final String LONE_HIGH_SURROGATE = Character.toString(Character.MIN_HIGH_SURROGATE);

	private static final String NOT_UNICODE = "Input should be a valid string, unable to parse raw data as a "
			+ "unicode string";

	/** What decoding a lone surrogate's three bytes as UTF-8 gives, one for each. */
	private static final String THREE_REPLACEMENTS = Character.toString(0xFFFD).repeat(3);

	static Stream<String> loneSurrogates() {
		return Stream.of(HIGH, LOW, "x" + HIGH, LOW + HIGH);
	}

	@ParameterizedTest
	@MethodSource("loneSurrogates")
	void read_loneSurrogateInStringWithALength_isRefused(String text) {
		List<BindingError> errors = CodecTestSupport.errors(object("name", text), Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "name", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogatesPastTheMaximumLength_areRefusedForTheSurrogateAlone() {
		List<BindingError> errors = CodecTestSupport.errors(object("name", HIGH.repeat(11)), Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "name", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateInStringWithAPattern_isRefused() {
		List<BindingError> errors = CodecTestSupport.errors("{\"tags\": [\"" + HIGH + "\"]}", Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "tags.0", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateInStringWithoutConstraints_isTakenAsItIs() {
		String input = "{\"id\": \"" + HIGH + "\", \"size\": 1}";

		Part part = RecordCodec.read(CodecTestSupport.json(input), Part.class);

		assertThat(part.id()).isEqualTo(LONE_HIGH_SURROGATE);
	}

	@Test
	void read_surrogatePairInStringWithALength_isAccepted() {
		Sample sample = RecordCodec.read(CodecTestSupport.json(object("name", HIGH + LOW)), Sample.class);

		assertThat(sample.name()).isEqualTo(Character.toString(Character.MIN_SUPPLEMENTARY_CODE_POINT));
	}

	@Test
	void read_loneSurrogateForALiteral_isRefusedRatherThanCompared() {
		List<BindingError> errors = CodecTestSupport.errors(object("version", HIGH), Versioned.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "version", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateForAChoiceOfStrings_isRefusedRatherThanCompared() {
		List<BindingError> errors = CodecTestSupport.errors(object("mode", HIGH), Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "mode", NOT_UNICODE));
	}

	@ParameterizedTest
	@ValueSource(strings = { "rate", "count", "enabled" })
	void read_loneSurrogateForANumberOrABoolean_isRefusedRatherThanParsed(String field) {
		List<BindingError> errors = CodecTestSupport.errors(object(field, "1" + HIGH), Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", field, NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateInAnUnknownKey_isTheRecordsOnlyError() {
		ObjectNode input = (ObjectNode) CodecTestSupport.json("{\"count\": 0}");
		input.put(LONE_HIGH_SURROGATE, 1).put("zz", 2);

		List<BindingError> errors = CodecTestSupport.errors(input, Sample.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateInAnUnknownKeyOfANestedRecord_isThatRecordsOnlyError() {
		ObjectNode input = (ObjectNode) CodecTestSupport.json("{\"figure\": {\"kind\": \"circle\", \"radius\": 0}}");
		((ObjectNode) input.get("figure")).put(LONE_HIGH_SURROGATE, 1);

		List<BindingError> errors = CodecTestSupport.errors(input, Drawing.class);

		assertThat(errors).containsExactly(CodecTestSupport.error("string_unicode", "figure.circle", NOT_UNICODE));
	}

	@Test
	void read_loneSurrogateAsAUnionsTag_isWrittenWithReplacementCharacters() {
		List<BindingError> errors = CodecTestSupport.errors("{\"figure\": {\"kind\": \"" + HIGH + "\"}}",
				Drawing.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("union_tag_invalid", "figure", "Input tag '" + THREE_REPLACEMENTS
					+ "' found using 'kind' does not match any of the expected tags: " + "'circle', 'square'"));
	}

	private static String object(String key, String value) {
		return "{\"" + key + "\": \"" + value + "\"}";
	}

	/**
	 * A UTF-16 code unit as a JSON escape: the only way a lone surrogate reaches ArchSim
	 * in UTF-8.
	 * @param unit the code unit
	 * @return its six-character escape
	 */
	private static String jsonEscape(char unit) {
		return "\\u%04x".formatted((int) unit);
	}

}
