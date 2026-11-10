package io.archsim.engine.text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON text read as Python's {@code json.loads} reads it: NaN and the infinities as
 * numbers, a key given twice in its first place with its last value, and nothing after
 * the value. Text that is not JSON is refused as {@code json} refuses it: where no value
 * starts, or anything follows the value, in its words; past the start, with Jackson's
 * description of the problem in its form ({@code ...: line 1 column 6 (char 5)}). JSON
 * nested deeper than it reads is a {@link JsonTooDeepException}.
 */
public final class PythonJsonReader {

	/**
	 * The deepest a document may nest. Python read deeper, to its C recursion limit
	 * (about 3,000 levels), and raised {@code RecursionError} past it.
	 */
	private static final int MAX_DEPTH = 1000;

	/**
	 * The most digits a number's whole part, fraction or exponent may have: Python's most
	 * for a whole number. Python reads floats of any length.
	 */
	private static final int MAX_NUMBER_DIGITS = 4300;

	private static final JsonMapper JSON = JsonMapper
		.builder(JsonFactory.builder()
			.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
			.streamReadConstraints(StreamReadConstraints.builder()
				.maxNestingDepth(MAX_DEPTH)
				.maxNumberLength(MAX_NUMBER_DIGITS)
				// keys as long as the text, as Python reads them: the caller bounds the
				// text
				.maxNameLength(Integer.MAX_VALUE)
				.build())
			.build())
		// what follows the value is refused in json's words, by read
		.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	/**
	 * U+FEFF, what Windows tools may write before the JSON, which {@code json} refuses.
	 */
	private static final char BYTE_ORDER_MARK = 0xFEFF;

	/** How Jackson says a document nests past the limit. */
	private static final String TOO_DEEP = "Document nesting depth";

	/**
	 * What may start a value besides a string, an object or an array, as {@code json}
	 * scans it.
	 */
	private static final List<String> WORDS = List.of("null", "true", "false", "NaN", "Infinity", "-Infinity");

	/** A number as {@code json}'s scanner reads one: ASCII digits, no leading zeros. */
	private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?");

	/** Where Jackson names the setting a limit comes from. */
	private static final Pattern API = Pattern.compile(", from `[^`]*`");

	private PythonJsonReader() {
	}

	/**
	 * The value JSON text holds.
	 * @param text the text
	 * @return its value
	 * @throws JsonDecodeException when the text is not JSON
	 * @throws JsonTooDeepException when it nests more than 1,000 levels deep
	 */
	public static JsonNode read(String text) {
		if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
			throw notJson("Unexpected UTF-8 BOM (decode using utf-8-sig)", text, 0);
		}
		int start = skipWhitespace(text, 0);
		if (!startsValue(text, start)) {
			throw notJson("Expecting value", text, start);
		}
		int scalarEnd = scalarEnd(text, start);
		if (scalarEnd > start) {
			// a word or number at the top: what follows it is extra, however it starts
			refuseExtraData(text, scalarEnd);
		}
		try (JsonParser parser = JSON.createParser(text)) {
			JsonNode value = JSON.readTree(parser);
			refuseExtraData(text, (int) parser.currentLocation().getCharOffset());
			return value;
		}
		catch (StreamConstraintsException ex) {
			if (String.valueOf(ex.getOriginalMessage()).startsWith(TOO_DEEP)) {
				throw new JsonTooDeepException("the JSON nests more than " + MAX_DEPTH + " levels deep", ex);
			}
			throw notJson(ex);
		}
		catch (JacksonException ex) {
			throw notJson(ex);
		}
	}

	/**
	 * Whether a value starts at a place, as {@code json}'s scanner looks for one.
	 * @param text the text
	 * @param start the place
	 * @return {@code true} when a string, object, array, word or number starts there
	 */
	private static boolean startsValue(String text, int start) {
		if (start >= text.length()) {
			return false;
		}
		char first = text.charAt(start);
		if (first == '"' || first == '{' || first == '[') {
			return true;
		}
		return scalarEnd(text, start) > start;
	}

	/**
	 * Where a word or number that starts at a place ends, as {@code json}'s scanner reads
	 * it: a word in full, else the longest number.
	 * @param text the text
	 * @param start the place
	 * @return where it ends; {@code start} when none starts there
	 */
	private static int scalarEnd(String text, int start) {
		for (String word : WORDS) {
			if (text.startsWith(word, start)) {
				return start + word.length();
			}
		}
		Matcher number = NUMBER.matcher(text).region(start, text.length());
		return number.lookingAt() ? number.end() : start;
	}

	/**
	 * Refuses anything but whitespace after the value, as {@code json} refuses it.
	 * @param text the text
	 * @param end where the value ends
	 * @throws JsonDecodeException when something follows the value
	 */
	private static void refuseExtraData(String text, int end) {
		int extra = skipWhitespace(text, end);
		if (extra < text.length()) {
			throw notJson("Extra data", text, extra);
		}
	}

	/**
	 * Where the whitespace {@code json} skips around a value ends.
	 * @param text the text
	 * @param from where the whitespace may start
	 * @return the first place from there that is not a space, tab, line feed or carriage
	 * return
	 */
	private static int skipWhitespace(String text, int from) {
		int place = from;
		while (place < text.length() && " \t\n\r".indexOf(text.charAt(place)) >= 0) {
			place++;
		}
		return place;
	}

	/**
	 * A refusal of text that is not JSON, in {@code json}'s words and form.
	 * @param problem what is wrong
	 * @param text the text
	 * @param place where, as an index
	 * @return the refusal
	 */
	private static JsonDecodeException notJson(String problem, String text, int place) {
		int line = 1;
		int lineStart = 0;
		for (int i = 0; i < place; i++) {
			if (text.charAt(i) == '\n') {
				line++;
				lineStart = i + 1;
			}
		}
		int column = text.codePointCount(lineStart, place) + 1;
		int character = text.codePointCount(0, place);
		return new JsonDecodeException(problem + ": line " + line + " column " + column + " (char " + character + ")",
				null);
	}

	/**
	 * A refusal of text that is not JSON, in the parser's words without the parser's API
	 * (``, from `StreamReadConstraints.getMaxNumberLength()` ``), in {@code json}'s form.
	 * @param ex what the parser said
	 * @return the refusal
	 */
	private static JsonDecodeException notJson(JacksonException ex) {
		String problem = API.matcher(String.valueOf(ex.getOriginalMessage())).replaceAll("");
		return new JsonDecodeException(problem + where(ex.getLocation()), ex);
	}

	private static String where(@Nullable TokenStreamLocation location) {
		if (location == null || TokenStreamLocation.NA.equals(location)) {
			return "";
		}
		return ": line " + location.getLineNr() + " column " + location.getColumnNr() + " (char "
				+ location.getCharOffset() + ")";
	}

}
