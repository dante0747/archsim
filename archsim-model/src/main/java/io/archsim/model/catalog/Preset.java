package io.archsim.model.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A named technology preset of a component type, such as PostgreSQL for a database. Its
 * settings take the technology's behaviour from the type's technology profiles; its
 * numbers (replicas, shards, sizes) are illustrative unless {@code sources} cites the
 * documentation that states them.
 *
 * @param id the preset's id
 * @param name what the editor calls it
 * @param technology the technology it sets
 * @param family the family the editor groups it in, such as {@code Relational}
 * @param description what it is, in a sentence
 * @param config the settings it sets, which differ from the type's defaults
 * @param tags words the editor's search finds it by
 * @param sources setting → the documentation that states its number
 */
public record Preset(String id, String name, String technology, String family, String description, ObjectNode config,
		List<String> tags, Map<String, String> sources) {

	/**
	 * Checks that the catalog gave every part, and copies the settings, tags and sources.
	 */
	public Preset {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(technology, "technology");
		Objects.requireNonNull(family, "family");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(config, "config");
		config = config.deepCopy();
		tags = List.copyOf(tags);
		sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
	}

	/**
	 * The settings it sets, as a copy: a record does not change.
	 * @return a copy of the settings
	 */
	@Override
	public ObjectNode config() {
		return this.config.deepCopy();
	}

	/**
	 * The value it sets a setting to.
	 * @param key the setting's JSON name
	 * @return a copy of the value; empty when the preset leaves the setting alone
	 */
	public Optional<JsonNode> setting(String key) {
		return Optional.ofNullable(this.config.get(key)).map(JsonNode::deepCopy);
	}

	/**
	 * The documentation that states the number it sets a setting to.
	 * @param key the setting's JSON name
	 * @return the documentation's link; empty when the number is illustrative
	 */
	public Optional<String> source(String key) {
		return Optional.ofNullable(this.sources.get(key)).filter((link) -> !link.isEmpty());
	}

}
