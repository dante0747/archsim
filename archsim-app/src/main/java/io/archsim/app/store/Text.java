package io.archsim.app.store;

/**
 * Text as Python measures, cuts and trims it: in characters (code points), not in Java's
 * UTF-16 units, and with Python's idea of white space.
 */
public final class Text {

	/** The character Python's {@code str.isspace} counts that Java's checks do not. */
	private static final int NEXT_LINE = 0x85;

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

	/**
	 * A text without the white space it starts and ends with, as Python's
	 * {@code text.strip()}: no-break spaces count, unlike for {@link String#strip()}.
	 * @param text the text
	 * @return the text, trimmed
	 */
	public static String strip(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && isSpace(text.codePointAt(start))) {
			start += Character.charCount(text.codePointAt(start));
		}
		while (end > start && isSpace(text.codePointBefore(end))) {
			end -= Character.charCount(text.codePointBefore(end));
		}
		return text.substring(start, end);
	}

	/**
	 * A text on one line, as Python's {@code " ".join(text.split())}: its words joined by
	 * single spaces.
	 * @param text the text
	 * @return its words, one space apart
	 */
	public static String oneLine(String text) {
		StringBuilder line = new StringBuilder(text.length());
		boolean space = false;
		for (int at = 0; at < text.length(); at += Character.charCount(text.codePointAt(at))) {
			int character = text.codePointAt(at);
			if (isSpace(character)) {
				space = !line.isEmpty();
			}
			else {
				if (space) {
					line.append(' ');
					space = false;
				}
				line.appendCodePoint(character);
			}
		}
		return line.toString();
	}

	/**
	 * Whether Python's {@code str.isspace} counts a character as white space: Java's
	 * white space, its space characters (no-break spaces among them) and next line.
	 * @param character the character
	 * @return {@code true} when it is white space
	 */
	private static boolean isSpace(int character) {
		return Character.isWhitespace(character) || Character.isSpaceChar(character) || character == NEXT_LINE;
	}

}
