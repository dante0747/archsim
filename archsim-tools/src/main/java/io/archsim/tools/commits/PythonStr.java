package io.archsim.tools.commits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The bits of CPython's {@code str} the check keeps: {@code repr()}, which quotes a
 * header or a description in a problem; {@code splitlines()}, which splits a message; and
 * whitespace as {@code str.isspace()} and {@code re}'s {@code \s} have it, which
 * {@code strip()} trims. Python's whitespace is wider than Java's: a no-break space, the
 * next line and the information separators are whitespace to Python, not to
 * {@code Character.isWhitespace}. The engine has these too ({@code PythonText}); the
 * check keeps its own, so that it runs from its source with the JDK alone.
 */
final class PythonStr {

	/** Python's whitespace: each range of code points, first and last. */
	private static final int[][] WHITESPACE = { { 0x09, 0x0D }, { 0x1C, 0x20 }, { 0x85, 0x85 }, { 0xA0, 0xA0 },
			{ 0x1680, 0x1680 }, { 0x2000, 0x200A }, { 0x2028, 0x2029 }, { 0x202F, 0x202F }, { 0x205F, 0x205F },
			{ 0x3000, 0x3000 } };

	/** What {@code str.splitlines()} splits on. */
	private static final Pattern LINE_BREAK = Pattern
		.compile("\\r\\n|[\\n\\r\\x0B\\x0C\\x1C\\x1D\\x1E\\x{85}\\x{2028}\\x{2029}]");

	private static final int DELETE = 0x7f;

	private static final int BYTE = 0xff;

	private static final int UNICODE_BMP = 0xffff;

	private PythonStr() {
	}

	/**
	 * A text as {@code repr()} writes it: in single quotes, or double ones when it holds
	 * a single quote and no double one; backslashes, the quote, tabs, line breaks and
	 * characters Python does not print escaped.
	 * @param text the text
	 * @return its repr
	 */
	static String repr(String text) {
		char quote = (text.indexOf('\'') >= 0 && text.indexOf('"') < 0) ? '"' : '\'';
		StringBuilder repr = new StringBuilder().append(quote);
		text.codePoints().forEach((point) -> repr.append(escaped(point, quote)));
		return repr.append(quote).toString();
	}

	/**
	 * A text's lines as {@code str.splitlines()} splits them: at any of Unicode's line
	 * breaks, without a last empty line after a final break.
	 * @param text the text
	 * @return its lines
	 */
	static List<String> splitlines(String text) {
		List<String> lines = new ArrayList<>(List.of(LINE_BREAK.split(text, -1)));
		if (!lines.isEmpty() && lines.getLast().isEmpty()) {
			lines.removeLast();
		}
		return lines;
	}

	/**
	 * Whether a text is empty or all Python's whitespace: {@code not text.strip()}.
	 * @param text the text
	 * @return whether it is blank
	 */
	static boolean isBlank(String text) {
		return text.codePoints().allMatch(PythonStr::isspace);
	}

	/**
	 * A text without Python's whitespace at either end: {@code str.strip()}.
	 * @param text the text
	 * @return the text, trimmed
	 */
	static String strip(String text) {
		int start = 0;
		while (start < text.length() && isspace(text.codePointAt(start))) {
			start += Character.charCount(text.codePointAt(start));
		}
		return rstrip(text.substring(start));
	}

	/**
	 * A text without Python's whitespace at its end: {@code str.rstrip()}.
	 * @param text the text
	 * @return the text, trimmed
	 */
	static String rstrip(String text) {
		int end = text.length();
		while (end > 0 && isspace(text.codePointBefore(end))) {
			end -= Character.charCount(text.codePointBefore(end));
		}
		return text.substring(0, end);
	}

	/**
	 * Whether a character is whitespace to Python: {@code str.isspace()}.
	 * @param point the character
	 * @return whether it is
	 */
	static boolean isspace(int point) {
		return Arrays.stream(WHITESPACE).anyMatch((range) -> range[0] <= point && point <= range[1]);
	}

	/**
	 * Python's whitespace as a regular expression's character class: what {@code \s}
	 * matched in Python's {@code re}.
	 * @return the class
	 */
	static String space() {
		return '[' + ranges() + ']';
	}

	/**
	 * Anything but Python's whitespace: what {@code \S} matched in Python's {@code re}.
	 * @return the class
	 */
	static String notSpace() {
		return '[' + "^" + ranges() + ']';
	}

	// the inside of a character class with every range of Python's whitespace
	private static String ranges() {
		return Arrays.stream(WHITESPACE)
			.map((range) -> String.format(Locale.ROOT, "\\x{%X}-\\x{%X}", range[0], range[1]))
			.collect(Collectors.joining());
	}

	private static String escaped(int point, char quote) {
		if (point == '\\' || point == quote) {
			return "\\" + (char) point;
		}
		if (point == '\t') {
			return "\\t";
		}
		if (point == '\n') {
			return "\\n";
		}
		if (point == '\r') {
			return "\\r";
		}
		if (printable(point)) {
			return Character.toString(point);
		}
		if (point <= BYTE) {
			return String.format(Locale.ROOT, "\\x%02x", point);
		}
		return (point <= UNICODE_BMP) ? String.format(Locale.ROOT, "\\u%04x", point)
				: String.format(Locale.ROOT, "\\U%08x", point);
	}

	// str.isprintable(): none of Unicode's other or separator characters, but the space
	private static boolean printable(int point) {
		if (point == ' ') {
			return true;
		}
		if (point < ' ' || point == DELETE) {
			return false;
		}
		return switch (Character.getType(point)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
					Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR ->
				false;
			default -> true;
		};
	}

}
