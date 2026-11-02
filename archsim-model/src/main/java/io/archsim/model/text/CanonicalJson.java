package io.archsim.model.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * JSON text exactly as Python's
 * {@code json.dumps(value, sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)} writes it: keys sorted by code point, no spaces, floats as their
 * repr, non-ASCII characters as they are. Hashes of it (a price snapshot's
 * {@code sha256}) then match the Python engine's.
 */
public final class CanonicalJson {

	/**
	 * The first character that is not a C0 control character; those before it are
	 * escaped.
	 */
	private static final char SPACE = 0x20;

	private CanonicalJson() {
	}

	/**
	 * The canonical JSON text of a value.
	 * @param value the JSON value
	 * @return its text, as Python's {@code json.dumps} writes it with these options
	 */
	public static String write(JsonNode value) {
		StringBuilder out = new StringBuilder();
		write(value, out);
		return out.toString();
	}

	private static void write(JsonNode value, StringBuilder out) {
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

	private static void array(JsonNode array, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < array.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			write(array.get(i), out);
		}
		out.append(']');
	}

	private static void object(JsonNode object, StringBuilder out) {
		List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(object.properties());
		properties.sort(Map.Entry.comparingByKey(CanonicalJson::compareCodePoints));
		out.append('{');
		for (int i = 0; i < properties.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			string(properties.get(i).getKey(), out);
			out.append(':');
			write(properties.get(i).getValue(), out);
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
	 * A string as Python's json module writes it without ASCII escaping.
	 * @param text the string
	 * @param out where its JSON text is written, quoted
	 */
	private static void string(String text, StringBuilder out) {
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
					if (character < SPACE) {
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
