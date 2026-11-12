package io.archsim.model.codec;

import tools.jackson.databind.JsonNode;

/**
 * pydantic encoding a string to UTF-8. A Python string, like a Java one, can hold half of
 * a surrogate pair, which UTF-8 cannot encode; JSON carries one as an escape. pydantic
 * passes a string it has nothing to check through as it is, but encodes it to check its
 * length or pattern, to compare it with a literal, to parse a number or a boolean from
 * it, and to find a record's unknown keys: there a lone surrogate is refused, with
 * {@code string_unicode}.
 */
final class Utf8Encoding {

	/** What a byte that is not UTF-8 decodes to, when decoding replaces it. */
	private static final String REPLACEMENT = Character.toString(0xFFFD);

	/** A lone surrogate's bytes as Python encodes it with {@code surrogatepass}. */
	private static final int SURROGATE_BYTES = 3;

	private Utf8Encoding() {
	}

	/**
	 * Whether pydantic cannot encode this text to UTF-8: it holds half of a surrogate
	 * pair.
	 * @param text the text
	 * @return whether the text has a lone surrogate
	 */
	static boolean cannotEncode(String text) {
		return text.codePoints().anyMatch(Utf8Encoding::isSurrogate);
	}

	/**
	 * Whether this input is a string pydantic cannot encode to UTF-8.
	 * @param in the input
	 * @return whether it is a string with a lone surrogate
	 */
	static boolean isUnencodableString(JsonNode in) {
		return in.isString() && cannotEncode(in.stringValue());
	}

	/**
	 * Adds pydantic's error for a string it cannot encode to UTF-8.
	 * @param at where the string is in the input, or the record whose key it is
	 * @param errors where the error is added
	 */
	static void refuse(Location at, Errors errors) {
		errors.add("string_unicode", at, Messages.STRING_UNICODE);
	}

	/**
	 * The text as pydantic writes it into a message: encoded with each lone surrogate
	 * kept as its three bytes ({@code surrogatepass}), then decoded with each byte that
	 * is not UTF-8 replaced, so three replacement characters for a lone surrogate.
	 * @param text the text
	 * @return the text with each lone surrogate replaced
	 */
	static String lossy(String text) {
		StringBuilder decoded = new StringBuilder(text.length());
		text.codePoints()
			.forEach((codePoint) -> decoded
				.append(isSurrogate(codePoint) ? REPLACEMENT.repeat(SURROGATE_BYTES) : Character.toString(codePoint)));
		return decoded.toString();
	}

	private static boolean isSurrogate(int codePoint) {
		return Character.getType(codePoint) == Character.SURROGATE;
	}

}
