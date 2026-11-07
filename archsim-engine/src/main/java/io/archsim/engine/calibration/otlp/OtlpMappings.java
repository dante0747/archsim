package io.archsim.engine.calibration.otlp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.otlp.OtlpMapping.CpuAttribute;
import io.archsim.engine.text.PythonStrings;
import io.archsim.engine.yaml.InvalidYamlException;
import io.archsim.engine.yaml.YamlDocuments;
import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlScalarException;
import io.archsim.engine.yaml.YamlValueException;
import io.archsim.model.text.PythonText;

/**
 * An OTLP mapping read from its YAML or JSON text, or from JSON, as the Python engine's
 * {@code load_otlp_mapping} reads it: {@code services} and {@code databases}, each name
 * to a part id, and an optional {@code cpuTime}, {@code {attribute, unit}}. Text is
 * always the mapping itself, never a path. What is wrong with it is an
 * {@link IllegalArgumentException} in the Python engine's words (its {@code ValueError}).
 */
public final class OtlpMappings {

	private static final Set<String> KEYS = Set.of("services", "databases", "cpuTime");

	private static final Set<String> CPU_KEYS = Set.of("attribute", "unit");

	/** The most characters of unknown keys a refusal repeats. */
	private static final int UNKNOWN_KEYS_SHOWN = 200;

	/** The longest attribute name. */
	private static final int MAX_ATTRIBUTE = 200;

	private OtlpMappings() {
	}

	/**
	 * A mapping from its text or from JSON.
	 * @param source the mapping's YAML or JSON text, the mapping as JSON, or JSON
	 * {@code null} for none
	 * @return the mapping; empty for none, or for text that holds nothing
	 * @throws IllegalArgumentException in the Python engine's words when the text is not
	 * valid YAML or the mapping is not one
	 * @throws YamlValueException when the YAML is beyond the bounds, or holds a value
	 * Python refuses
	 * @throws YamlKeyException when it holds a scalar Python looks up in vain
	 * @throws YamlScalarException when it holds another scalar Python fails to read
	 */
	public static Optional<OtlpMapping> load(JsonNode source) {
		JsonNode data = source;
		if (source.isString()) {
			try {
				data = YamlDocuments.one(source.stringValue());
			}
			catch (InvalidYamlException ex) {
				throw new IllegalArgumentException("the OTLP mapping is not valid YAML: " + ex.description());
			}
		}
		return data.isNull() ? Optional.empty() : Optional.of(check(data));
	}

	private static OtlpMapping check(JsonNode mapping) {
		if (!mapping.isObject()) {
			throw new IllegalArgumentException(
					"the OTLP mapping must be an object: {services: {...}, databases: {...}}");
		}
		List<String> unknown = mapping.propertyNames()
			.stream()
			.filter((key) -> !KEYS.contains(key))
			.sorted(PythonStrings::compare)
			.toList();
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("the OTLP mapping has unknown keys ("
					+ firstCodePoints(String.join(", ", unknown), UNKNOWN_KEYS_SHOWN)
					+ "): it takes services, databases and cpuTime");
		}
		return new OtlpMapping(names(mapping, "services"), names(mapping, "databases"),
				cpuAttribute(mapping.path("cpuTime")));
	}

	/**
	 * A section of names to part ids.
	 * @param mapping the mapping
	 * @param key the section's key
	 * @return each name's part id; none for a section that is not there or not truthy
	 */
	private static Map<String, String> names(JsonNode mapping, String key) {
		JsonNode section = mapping.path(key);
		Map<String, String> names = new LinkedHashMap<>();
		if (!PythonText.truthy(section)) {
			return names;
		}
		if (!section.isObject()) {
			throw notNames(key);
		}
		for (Map.Entry<String, JsonNode> entry : section.properties()) {
			if (!entry.getValue().isString()) {
				throw notNames(key);
			}
			names.put(entry.getKey(), entry.getValue().stringValue());
		}
		return names;
	}

	/**
	 * The CPU-time attribute: {@code {attribute, unit}}, the unit {@code ms} unless
	 * given.
	 * @param cpu the {@code cpuTime} section
	 * @return the attribute; {@code null} when the section is not there or is null
	 */
	private static @Nullable CpuAttribute cpuAttribute(JsonNode cpu) {
		if (cpu.isMissingNode() || cpu.isNull()) {
			return null;
		}
		Optional<CpuUnit> unit = unit(cpu);
		JsonNode attribute = cpu.path("attribute");
		if (!cpu.isObject() || !CPU_KEYS.containsAll(cpu.propertyNames()) || !isAttributeName(attribute)
				|| unit.isEmpty()) {
			throw new IllegalArgumentException(
					"the OTLP mapping's 'cpuTime' is {attribute: <span attribute name>, unit: s|ms|us|ns}");
		}
		return new CpuAttribute(attribute.stringValue(), unit.get());
	}

	private static Optional<CpuUnit> unit(JsonNode cpu) {
		if (!cpu.has("unit")) {
			return Optional.of(CpuUnit.MILLISECONDS);
		}
		JsonNode unit = cpu.get("unit");
		return unit.isString() ? CpuUnit.named(unit.stringValue()) : Optional.empty();
	}

	/**
	 * Whether a value names an attribute.
	 * @param attribute the value
	 * @return whether it is text that is not blank and has at most 200 characters
	 */
	private static boolean isAttributeName(JsonNode attribute) {
		return attribute.isString() && !PythonStrings.words(attribute.stringValue()).isEmpty()
				&& attribute.stringValue().codePointCount(0, attribute.stringValue().length()) <= MAX_ATTRIBUTE;
	}

	private static IllegalArgumentException notNames(String key) {
		return new IllegalArgumentException("the OTLP mapping's '" + key + "' must map names to part ids");
	}

	private static String firstCodePoints(String text, int count) {
		int codePoints = text.codePointCount(0, text.length());
		return (codePoints <= count) ? text : text.substring(0, text.offsetByCodePoints(0, count));
	}

}
