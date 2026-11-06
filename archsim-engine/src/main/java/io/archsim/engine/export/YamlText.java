package io.archsim.engine.export;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.NumberText;
import io.archsim.model.text.PythonJson;
import io.archsim.model.text.PythonText;

/**
 * YAML text as the exports write it, in block style: each scalar plain unless YAML could
 * read it as something else, then quoted as JSON. A small writer of its own, not a YAML
 * library, so that every export reads exactly as the Python engine wrote it.
 */
final class YamlText {

	/**
	 * Text that may stay plain. It may end in one newline, as Python's {@code $} allows,
	 * so a text ending in a newline is written plain, as the Python engine wrote it.
	 */
	private static final Pattern PLAIN = Pattern.compile("[A-Za-z_/.][A-Za-z0-9_./@:-]*\n?");

	/** Words YAML reads as a boolean or null, in any case. */
	private static final Pattern RESERVED = Pattern.compile("(?i)(true|false|yes|no|on|off|null|~|y|n)\n?");

	/** From this size on, a whole number is written as Python's repr of the float. */
	private static final double EXPONENT_FROM = 1e21;

	/** {@link #EXPONENT_FROM} as a whole number, to compare whole numbers exactly. */
	private static final BigInteger WHOLE_EXPONENT_FROM = BigDecimal.valueOf(EXPONENT_FROM).toBigIntegerExact();

	private static final String INDENT = "  ";

	private YamlText() {
	}

	/**
	 * The YAML text of a value, without a trailing newline.
	 * @param value the value
	 * @return its lines
	 */
	static String write(JsonNode value) {
		return String.join("\n", lines(value, ""));
	}

	/**
	 * Lines as YAML comments.
	 * @param lines the lines
	 * @return each line after a {@code #}, an empty one as a {@code #} alone
	 */
	static String comments(List<String> lines) {
		return lines.stream().map((line) -> line.isEmpty() ? "#" : "# " + line).collect(Collectors.joining("\n"));
	}

	/**
	 * A number as the exports write it: a whole number below 1e21 as JavaScript writes
	 * it, without a point; any other as Python's repr ({@code 0.5}, {@code 1e-05},
	 * {@code 1e+21}).
	 * @param value the number
	 * @return its text; {@code null} for a number that is not finite
	 */
	static String number(double value) {
		if (!Double.isFinite(value)) {
			return "null";
		}
		return (value == Math.rint(value) && Math.abs(value) < EXPONENT_FROM) ? NumberText.plain(value)
				: PythonText.repr(value);
	}

	/**
	 * A text as a scalar: plain unless YAML would read it as a boolean, null, a number or
	 * a map.
	 * @param text the text
	 * @return the scalar
	 */
	static String scalar(String text) {
		boolean plain = PLAIN.matcher(text).matches() && !RESERVED.matcher(text).matches() && !text.endsWith(":")
				&& !text.contains(": ");
		return plain ? text : PythonJson.quote(text);
	}

	private static String scalar(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return "null";
		}
		if (value.isBoolean()) {
			return String.valueOf(value.booleanValue());
		}
		if (value.isIntegralNumber()) {
			BigInteger whole = value.bigIntegerValue();
			return (whole.abs().compareTo(WHOLE_EXPONENT_FROM) < 0) ? whole.toString()
					: PythonText.repr(whole.doubleValue());
		}
		if (value.isNumber()) {
			return number(value.doubleValue());
		}
		return scalar(value.stringValue());
	}

	private static List<String> lines(JsonNode value, String pad) {
		if (value.isArray()) {
			return items(value, pad);
		}
		if (value.isObject()) {
			return entries(value, pad);
		}
		return List.of(pad + scalar(value));
	}

	private static List<String> items(JsonNode list, String pad) {
		List<String> lines = new ArrayList<>();
		for (JsonNode item : list) {
			if (!item.isContainer()) {
				lines.add(pad + "- " + scalar(item));
			}
			else if (item.isEmpty()) {
				lines.add(pad + (item.isArray() ? "- []" : "- {}"));
			}
			else if (item.isArray()) {
				lines.add(pad + "-");
				lines.addAll(lines(item, pad + INDENT));
			}
			else {
				List<String> inner = lines(item, pad + INDENT);
				lines.add(pad + "- " + inner.getFirst().substring(pad.length() + INDENT.length()));
				lines.addAll(inner.subList(1, inner.size()));
			}
		}
		return lines;
	}

	private static List<String> entries(JsonNode map, String pad) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : map.properties()) {
			String key = pad + scalar(entry.getKey()) + ":";
			JsonNode value = entry.getValue();
			if (!value.isContainer()) {
				lines.add(key + " " + scalar(value));
			}
			else if (value.isEmpty()) {
				lines.add(key + (value.isArray() ? " []" : " {}"));
			}
			else {
				lines.add(key);
				lines.addAll(lines(value, pad + INDENT));
			}
		}
		return lines;
	}

}
