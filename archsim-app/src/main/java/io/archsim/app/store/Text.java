package io.archsim.app.store;

/**
 * Text measured and cut in characters (code points), as Python counts and slices it, not
 * in Java's UTF-16 units.
 */
public final class Text {

	private Text() {
	}

	/**
	 * How many characters a text has.
	 * @param text the text
	 * @return its code points
	 */
	public static int length(String text) {
		return text.codePointCount(0, text.length());
	}

	/**
	 * A text's start, as Python's {@code text[:limit]}.
	 * @param text the text
	 * @param limit the most characters to keep
	 * @return the text, or its first {@code limit} characters
	 */
	public static String cut(String text, int limit) {
		return (length(text) > limit) ? text.substring(0, text.offsetByCodePoints(0, limit)) : text;
	}

}
