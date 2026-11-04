package io.archsim.model.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One setting of a config record, as the catalog describes it to the editor and the
 * analysis reads it.
 *
 * @param key the setting's JSON name
 * @param label what the inspector calls it
 * @param unit its unit, such as {@code ms}; {@code null} for none
 * @param kind the inspector's kind of input: {@code number}, {@code distribution},
 * {@code loadCurve}, ...
 * @param assumption whether it is a performance input the analysis lists with where its
 * value came from
 * @param visibleWhen the settings that must have these values for it to apply; empty when
 * it always applies
 * @param defaultValue its default; JSON {@code null} for none
 */
public record Setting(String key, String label, @Nullable String unit, String kind, boolean assumption,
		Map<String, JsonNode> visibleWhen, JsonNode defaultValue) {

	/**
	 * Copies the conditions and the default.
	 */
	public Setting {
		Map<String, JsonNode> conditions = new LinkedHashMap<>();
		visibleWhen.forEach((setting, value) -> conditions.put(setting, value.deepCopy()));
		visibleWhen = Collections.unmodifiableMap(conditions);
		defaultValue = defaultValue.deepCopy();
	}

	/**
	 * The settings it depends on, each value a copy: a record does not change.
	 * @return the conditions
	 */
	@Override
	public Map<String, JsonNode> visibleWhen() {
		Map<String, JsonNode> conditions = new LinkedHashMap<>();
		this.visibleWhen.forEach((setting, value) -> conditions.put(setting, value.deepCopy()));
		return Collections.unmodifiableMap(conditions);
	}

	/**
	 * Its default, as a copy: a record does not change.
	 * @return a copy of the default
	 */
	@Override
	public JsonNode defaultValue() {
		return this.defaultValue.deepCopy();
	}

}
