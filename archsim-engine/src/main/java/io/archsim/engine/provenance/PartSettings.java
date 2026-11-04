package io.archsim.engine.provenance;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.catalog.TypeFacts;
import io.archsim.model.document.NodeSpec;

/**
 * One part's settings as written, with its type's facts and its technology, read once.
 */
final class PartSettings {

	/** The setting a part's technology is chosen with. */
	private static final String TECHNOLOGY = "technology";

	private final String id;

	private final ObjectNode config;

	private final TypeFacts type;

	private final @Nullable String technology;

	PartSettings(NodeSpec node, TypeFacts type) {
		this.id = node.id();
		this.config = node.config();
		this.type = type;
		JsonNode chosen = orDefault(TECHNOLOGY);
		this.technology = chosen.isString() ? chosen.stringValue() : null;
	}

	/**
	 * The part's id.
	 * @return the id
	 */
	String id() {
		return this.id;
	}

	/**
	 * The part's type.
	 * @return its facts
	 */
	TypeFacts type() {
		return this.type;
	}

	/**
	 * The part's technology: as written, else its type's default.
	 * @return the technology; empty when it is not a name
	 */
	Optional<String> technology() {
		return Optional.ofNullable(this.technology);
	}

	/**
	 * Whether the part writes a setting.
	 * @param key the setting's JSON name
	 * @return whether its config has the setting, even as {@code null}
	 */
	boolean has(String key) {
		return this.config.has(key);
	}

	/**
	 * A setting as written.
	 * @param key the setting's JSON name
	 * @return its value; a missing node (Python's {@code None}) when it is not written
	 */
	JsonNode written(String key) {
		return this.config.path(key);
	}

	/**
	 * A setting as written, else its type's default.
	 * @param key the setting's JSON name
	 * @return its value
	 */
	JsonNode orDefault(String key) {
		return this.config.has(key) ? this.config.get(key) : this.type.defaultValue(key);
	}

}
