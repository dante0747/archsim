package io.archsim.engine.assumptions;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.Tier;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * How the page writes a value, its basis, a setting's label and unit, a type's
 * technologies of a tier, and its tables; and the settings' descriptions by name.
 */
final class PageText {

	/** The repository's root, seen from the page in {@code docs/}. */
	private static final String REPOSITORY = "../";

	private static final String CELL = " | ";

	private PageText() {
	}

	/**
	 * A setting's value as text, with its unit: a number, a distribution, yes or no,
	 * none, or anything else in code, as Python's {@code str()} writes it.
	 * @param value the value as the catalog writes it
	 * @param unit its unit; {@code null} for none
	 * @return the value as the page shows it
	 */
	static String value(JsonNode value, @Nullable String unit) {
		if (value.isNull() || value.isMissingNode()) {
			return "none";
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? "yes" : "no";
		}
		String suffix = (unit == null || unit.isEmpty()) ? "" : " " + unit;
		if (value.isNumber()) {
			return number(value) + suffix;
		}
		return distribution(value, suffix).orElseGet(() -> "`" + PythonText.str(value) + "`");
	}

	/**
	 * Where a value comes from.
	 * @param source the documentation that states it; {@code null} for none
	 * @return {@code illustrative}, or {@code documented} with a link named by its host
	 */
	static String basis(@Nullable String source) {
		if (source == null || source.isEmpty()) {
			return "illustrative";
		}
		String authority = Objects.requireNonNullElse(URI.create(source).getRawAuthority(), "");
		return "documented: [" + withoutPrefix(withoutPrefix(authority, "www."), "docs.") + "](" + source + ")";
	}

	/**
	 * A value ArchSim's own benchmark measured (ADR-0085).
	 * @param results the file of its results, from the repository's root
	 * @return {@code measured} with a link to the file from the page, named by its folder
	 * (the file itself at the root)
	 */
	static String measured(String results) {
		int slash = results.lastIndexOf('/');
		String name = (slash < 0) ? results : results.substring(0, slash);
		return "measured: [" + name + "](" + REPOSITORY + results + ")";
	}

	/**
	 * A Markdown table.
	 * @param head the column titles
	 * @param rows the rows; pipes in a cell are escaped
	 * @return the table's lines
	 */
	static List<String> table(List<String> head, List<List<String>> rows) {
		List<String> lines = new ArrayList<>();
		lines.add("| " + String.join(CELL, head) + " |");
		lines.add("|" + String.join("|", head.stream().map((title) -> " --- ").toList()) + "|");
		for (List<String> row : rows) {
			lines.add("| " + String.join(CELL, row.stream().map((cell) -> cell.replace("|", "\\|")).toList()) + " |");
		}
		return lines;
	}

	/**
	 * A type's technologies of one tier, in code.
	 * @param technologies the type's technologies' profiles, by name
	 * @param tier the tier
	 * @return the technologies' names, or {@code -} when none is of that tier
	 */
	static String technologies(Map<String, TechnologyProfile> technologies, Tier tier) {
		List<String> named = technologies.entrySet()
			.stream()
			.filter((technology) -> technology.getValue().tier() == tier)
			.map((technology) -> "`" + technology.getKey() + "`")
			.toList();
		return named.isEmpty() ? "-" : String.join(", ", named);
	}

	/**
	 * Settings by their names.
	 * @param fields the catalog's descriptions of a config's settings
	 * @return each description by its setting's name, in order
	 */
	static Map<String, JsonNode> byKey(JsonNode fields) {
		Map<String, JsonNode> byKey = new LinkedHashMap<>();
		fields.forEach((field) -> byKey.put(field.path("key").stringValue(), field));
		return byKey;
	}

	/**
	 * A setting's label with its name, or its name alone when it is not described.
	 * @param fields the settings' descriptions, by name
	 * @param key the setting's name
	 * @return the label
	 */
	static String label(Map<String, JsonNode> fields, String key) {
		JsonNode field = fields.get(key);
		return (field != null) ? labelOf(field) : "`" + key + "`";
	}

	/**
	 * A described setting's label with its name.
	 * @param field the setting's description
	 * @return the label, such as {@code GC pause (`gcPauseMs`)}
	 */
	static String labelOf(JsonNode field) {
		return field.path("label").stringValue() + " (`" + field.path("key").stringValue() + "`)";
	}

	/**
	 * A setting's unit.
	 * @param fields the settings' descriptions, by name
	 * @param key the setting's name
	 * @return its unit; {@code null} when it has none or is not described
	 */
	static @Nullable String unit(Map<String, JsonNode> fields, String key) {
		JsonNode field = fields.get(key);
		if (field == null) {
			return null;
		}
		return unitOf(field);
	}

	/**
	 * A described setting's unit.
	 * @param field the setting's description
	 * @return its unit; {@code null} when it has none
	 */
	static @Nullable String unitOf(JsonNode field) {
		JsonNode unit = field.path("unit");
		return unit.isString() ? unit.stringValue() : null;
	}

	/**
	 * A distribution the page knows, with its unit.
	 * @param value the value
	 * @param suffix the unit after a space, or nothing
	 * @return the distribution as text; empty for a value that is not one
	 */
	private static Optional<String> distribution(JsonNode value, String suffix) {
		JsonNode kind = value.path("kind");
		if (!kind.isString()) {
			return Optional.empty();
		}
		return switch (kind.stringValue()) {
			case "constant" -> Optional.of(number(value.path("value")) + suffix);
			case "exponential" -> Optional.of("exponential, mean " + number(value.path("mean")) + suffix);
			case "lognormal" -> Optional.of("lognormal, median " + number(value.path("median")) + suffix + ", p95 "
					+ number(value.path("p95")) + suffix);
			case "uniform" ->
				Optional.of("uniform " + number(value.path("min")) + "–" + number(value.path("max")) + suffix);
			default -> Optional.empty();
		};
	}

	/**
	 * A number as Python writes {@code f"{v:,}"} of a whole number and {@code f"{v:,g}"}
	 * of a fraction.
	 * @param number the number
	 * @return the number as text
	 */
	private static String number(JsonNode number) {
		return number.isIntegralNumber() ? PythonFormat.format(number.longValue(), ",")
				: PythonFormat.format(number.doubleValue(), ",g");
	}

	private static String withoutPrefix(String text, String prefix) {
		return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
	}

}
