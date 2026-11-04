package io.archsim.model.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * JSON text exactly as Python's
 * {@code json.dumps(value, sort_keys=True, separators=(",", ":"))} writes it: keys sorted
 * by code point, no spaces, floats as their repr, and non-ASCII characters either as they
 * are ({@code ensure_ascii=False}) or escaped. Hashes of it (a price snapshot's
 * {@code sha256}, a report's digest) then match the Python engine's.
 */
public final class CanonicalJson {

	/**
	 * The first character that is not a C0 control character; those before it are
	 * escaped.
	 */
	private static final char SPACE = 0x20;

	/** The last printable ASCII character, {@code ~}. */
	private static final char TILDE = 0x7e;

	private static final CanonicalJson AS_IS = new CanonicalJson(Character.MAX_VALUE);

	private static final CanonicalJson ASCII = new CanonicalJson(TILDE);

	/** The last character written as it is; the characters after it are escaped. */
	private final char lastUnescaped;

	private CanonicalJson(char lastUnescaped) {
		this.lastUnescaped = lastUnescaped;
	}

	/**
	 * The canonical JSON text of a value, non-ASCII characters as they are
	 * ({@code ensure_ascii=False}).
	 * @param value the JSON value
	 * @return its text, as Python's {@code json.dumps} writes it with these options
	 */
	public static String write(JsonNode value) {
		return AS_IS.text(value);
	}

	/**
	 * The canonical JSON text of a value in printable ASCII, Python's default
	 * ({@code ensure_ascii=True}): every other character is escaped with its four
	 * hexadecimal digits, a character beyond the Basic Multilingual Plane as its two
	 * surrogates.
	 * @param value the JSON value
	 * @return its text, as Python's {@code json.dumps} writes it with these options
	 */
	public static String writeAscii(JsonNode value) {
		return ASCII.text(value);
	}

	private String text(JsonNode value) {
		StringBuilder out = new StringBuilder();
		append(value, out);
		return out.toString();
	}

	private void append(JsonNode value, StringBuilder out) {
		if (value.isNull() || value.isMissingNode()) {
			out.append("null");
		}
		else if (value.isBoolean()) {
			out.append(value.booleanValue());
		}
		else if (value.isIntegralNumber()) {
			out.append(value.bigIntegerValue());
		}
		else if (value.isNumber()) {
			out.append(number(value.doubleValue()));
		}
		else if (value.isString()) {
			string(value.stringValue(), out);
		}
		else if (value.isArray()) {
			array(value, out);
		}
		else {
			object(value, out);
		}
	}

	private void array(JsonNode array, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < array.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			append(array.get(i), out);
		}
		out.append(']');
	}

	private void object(JsonNode object, StringBuilder out) {
		List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(object.properties());
		properties.sort(Map.Entry.comparingByKey(CanonicalJson::compareCodePoints));
		out.append('{');
		for (int i = 0; i < properties.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			string(properties.get(i).getKey(), out);
			out.append(':');
			append(properties.get(i).getValue(), out);
		}
		out.append('}');
	}

	/**
	 * A float as Python's json module writes it: its repr, and NaN and the infinities by
	 * name.
	 * @param value the float
	 * @return its JSON text
	 */
	private static String number(double value) {
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return (value > 0) ? "Infinity" : "-Infinity";
		}
		return PythonText.repr(value);
	}

	/**
	 * A string as Python's json module writes it.
	 * @param text the string
	 * @param out where its JSON text is written, quoted
	 */
	private void string(String text, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char character = text.charAt(i);
			switch (character) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (character < SPACE || character > this.lastUnescaped) {
						out.append("\\u%04x".formatted((int) character));
					}
					else {
						out.append(character);
					}
				}
			}
		}
		out.append('"');
	}

	/**
	 * Python's string order: by code point (Java's {@code compareTo} orders UTF-16
	 * units).
	 * @param left a string
	 * @param right another string
	 * @return below zero, zero or above zero as {@code left} comes before, with or after
	 * {@code right}
	 */
	private static int compareCodePoints(String left, String right) {
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length()) {
			int leftCodePoint = left.codePointAt(leftIndex);
			int rightCodePoint = right.codePointAt(rightIndex);
			if (leftCodePoint != rightCodePoint) {
				return Integer.compare(leftCodePoint, rightCodePoint);
			}
			leftIndex += Character.charCount(leftCodePoint);
			rightIndex += Character.charCount(rightCodePoint);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

}
