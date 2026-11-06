package io.archsim.engine.imports;

import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code terraform show -json} output read as Python's {@code json.loads} reads it: any
 * value at the top, {@code NaN} and {@code Infinity} as floats, a key written twice in
 * its first place with its last value, and nothing after the value. Text that is not JSON
 * is refused as the Python importer refused it; where no value starts, in {@code json}'s
 * words, else in the parser's.
 */
final class TerraformJson {

	/** The deepest a document may nest; Python's own limit is its C recursion limit. */
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
				// keys as long as the text, as Python reads them: the request's size
				// bounds them
				.maxNameLength(Integer.MAX_VALUE)
				.build())
			.build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	/** What Windows tools may write before the JSON, which json refuses. */
	private static final String BYTE_ORDER_MARK = "\ufeff";

	/** How Jackson says a document nests past the limit. */
	private static final String TOO_DEEP = "Document nesting depth";

	/**
	 * What may start a value besides a string, an object or an array, as json scans it.
	 */
	private static final List<String> WORDS = List.of("null", "true", "false", "NaN", "Infinity", "-Infinity");

	private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)");

	private TerraformJson() {
	}

	/**
	 * A plan's or state's JSON.
	 * @param text the text
	 * @return its value
	 * @throws ImportRefusedException when the text is not JSON, or nests too deeply
	 */
	static JsonNode read(String text) {
		if (text.startsWith(BYTE_ORDER_MARK)) {
			throw notJson("Unexpected UTF-8 BOM (decode using utf-8-sig)", text, 0);
		}
		int start = skipWhitespace(text);
		if (!startsValue(text, start)) {
			throw notJson("Expecting value", text, start);
		}
		try {
			return JSON.readTree(text);
		}
		catch (StreamConstraintsException ex) {
			if (String.valueOf(ex.getOriginalMessage()).startsWith(TOO_DEEP)) {
				throw new ImportRefusedException("the text nests too deeply to read as " + TerraformImport.SHAPE, ex);
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
		return WORDS.stream().anyMatch((word) -> text.startsWith(word, start))
				|| NUMBER.matcher(text).region(start, text.length()).lookingAt();
	}

	/**
	 * Where the whitespace {@code json} skips at the start ends.
	 * @param text the text
	 * @return the first place that is not a space, tab, line feed or carriage return
	 */
	private static int skipWhitespace(String text) {
		int place = 0;
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
	private static ImportRefusedException notJson(String problem, String text, int place) {
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
		return refusal(problem + ": line " + line + " column " + column + " (char " + character + ")", null);
	}

	/**
	 * A refusal of text that is not JSON, in the parser's words, in {@code json}'s form.
	 * @param ex what the parser said
	 * @return the refusal
	 */
	private static ImportRefusedException notJson(JacksonException ex) {
		TokenStreamLocation location = ex.getLocation();
		String problem = String.valueOf(ex.getOriginalMessage());
		if (location == null || TokenStreamLocation.NA.equals(location)) {
			return refusal(problem, ex);
		}
		return refusal(problem + ": line " + location.getLineNr() + " column " + location.getColumnNr() + " (char "
				+ location.getCharOffset() + ")", ex);
	}

	private static ImportRefusedException refusal(String detail, @Nullable Throwable cause) {
		String message = "not JSON: run `terraform show -json plan.out` (or state) first (" + detail + ")";
		return (cause != null) ? new ImportRefusedException(message, cause) : new ImportRefusedException(message);
	}

}
