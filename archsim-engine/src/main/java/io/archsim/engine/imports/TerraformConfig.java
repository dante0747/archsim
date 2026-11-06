package io.archsim.engine.imports;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings of a part a Terraform resource becomes, in the order the Python set them.
 * A setting may be unset (Python's {@code None}): the part leaves it out. With them, how
 * some numbers were derived, and the part's label when it is not its name.
 */
final class TerraformConfig {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final ObjectNode settings = NODES.objectNode();

	private final Map<String, String> notes = new LinkedHashMap<>();

	private @Nullable String label;

	/**
	 * Sets a setting.
	 * @param key the setting
	 * @param value its value; {@code null} or missing for unset
	 * @return this config
	 */
	TerraformConfig put(String key, @Nullable JsonNode value) {
		this.settings.set(key, (value == null || value.isMissingNode()) ? NODES.nullNode() : value);
		return this;
	}

	TerraformConfig put(String key, String value) {
		return put(key, NODES.stringNode(value));
	}

	TerraformConfig put(String key, boolean value) {
		return put(key, NODES.booleanNode(value));
	}

	/**
	 * A setting.
	 * @param key the setting
	 * @return its value; a missing or null node when it is unset
	 */
	JsonNode get(String key) {
		return this.settings.path(key);
	}

	/**
	 * Notes how numbers were derived, in place of any noted before.
	 * @param how how they were derived
	 * @param keys the settings, in order
	 */
	void derived(String how, String... keys) {
		this.notes.clear();
		for (String key : keys) {
			this.notes.put(key, how);
		}
	}

	/**
	 * How numbers were derived.
	 * @return each one's derivation, by setting
	 */
	Map<String, String> notes() {
		return Collections.unmodifiableMap(this.notes);
	}

	/**
	 * Labels the part otherwise than by its name.
	 * @param text the label
	 * @return this config
	 */
	TerraformConfig label(String text) {
		this.label = text;
		return this;
	}

	@Nullable String label() {
		return this.label;
	}

	/**
	 * The settings the part gets.
	 * @return a copy of the settings that are set, in order
	 */
	ObjectNode written() {
		ObjectNode written = NODES.objectNode();
		this.settings.properties().forEach((setting) -> {
			if (!setting.getValue().isNull()) {
				written.set(setting.getKey(), setting.getValue());
			}
		});
		return written;
	}

}
