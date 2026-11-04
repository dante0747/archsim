package io.archsim.engine.cost;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.catalog.Preset;
import io.archsim.model.catalog.TypeFacts;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.NodeSpec;

/**
 * One part's settings as the cost reads them, leniently, as the Python engine reads a
 * design's JSON (ADR-0038): a setting as written, or its type's default where the config
 * leaves it out or empty.
 */
final class CostPart {

	private final NodeSpec node;

	private final ObjectNode config;

	private final @Nullable TypeFacts type;

	private final Optional<ComponentSpec<?>> spec;

	CostPart(NodeSpec node, ComponentRegistry registry, ComponentFacts facts) {
		this.node = node;
		this.config = node.config();
		this.type = facts.type(node.type()).orElse(null);
		this.spec = registry.find(node.type());
	}

	/**
	 * The part.
	 * @return the part as the design has it
	 */
	NodeSpec node() {
		return this.node;
	}

	/**
	 * The part's id.
	 * @return the id
	 */
	String id() {
		return this.node.id();
	}

	/**
	 * The part's component type.
	 * @return the type's name
	 */
	String type() {
		return this.node.type();
	}

	/**
	 * The part's label, or its id when it has none.
	 * @return the label
	 */
	String label() {
		return this.node.name();
	}

	/**
	 * A setting as written.
	 * @param key the setting's JSON name
	 * @return its value; a missing node when the config leaves it out
	 */
	JsonNode written(String key) {
		return this.config.path(key);
	}

	/**
	 * A number setting as written.
	 * @param key the setting's JSON name
	 * @return its value when it is a finite number; {@code null} otherwise
	 */
	@Nullable Double writtenNumber(String key) {
		return number(written(key));
	}

	/**
	 * Whether a setting is written as this text.
	 * @param key the setting's JSON name
	 * @param text the text
	 * @return whether the config has it so
	 */
	boolean writtenIs(String key, String text) {
		JsonNode value = written(key);
		return value.isString() && text.equals(value.stringValue());
	}

	/**
	 * A setting in effect: as written, else its type's default ({@code config_value}).
	 * @param key the setting's JSON name
	 * @return its value; JSON {@code null} when neither has it
	 */
	JsonNode value(String key) {
		JsonNode value = written(key);
		if (!value.isNull() && !value.isMissingNode()) {
			return value;
		}
		return (this.type != null) ? this.type.defaultValue(key) : JsonNodeFactory.instance.nullNode();
	}

	/**
	 * A number setting in effect.
	 * @param key the setting's JSON name
	 * @return its value when it is a finite number; {@code null} otherwise
	 */
	@Nullable Double valueNumber(String key) {
		return number(value(key));
	}

	/**
	 * A number setting's default for the part's type.
	 * @param key the setting's JSON name
	 * @return the default when it is a finite number; {@code null} otherwise
	 */
	@Nullable Double defaultNumber(String key) {
		return (this.type != null) ? number(this.type.defaultValue(key)) : null;
	}

	/**
	 * The part's technology, as written or its type's default.
	 * @return the technology; {@code null} when it is not a name
	 */
	@Nullable String technology() {
		JsonNode technology = value("technology");
		return technology.isString() ? technology.stringValue() : null;
	}

	/**
	 * Whether the part's technology is one of some.
	 * @param technologies the technologies
	 * @return whether it has one of them; {@code false} without a technology
	 */
	boolean technologyIn(Set<String> technologies) {
		String technology = technology();
		return technology != null && technologies.contains(technology);
	}

	/**
	 * The part's type's presets.
	 * @return the presets; none for an unknown type
	 */
	List<Preset> presets() {
		return (this.type != null) ? this.type.presets() : List.of();
	}

	/**
	 * The settings over the type's defaults, a setting written as {@code null} kept, as
	 * the instance types read them; {@link #value} reads a {@code null} as unset instead.
	 * @return the settings
	 */
	LenientSettings overDefaults() {
		return this.spec.map((type) -> LenientSettings.overDefaults(type, this.config))
			.orElseGet(() -> LenientSettings.of(this.config));
	}

	/**
	 * Python's {@code num}: a finite number, a boolean not being one.
	 * @param value a JSON value
	 * @return the number; {@code null} for anything else
	 */
	static @Nullable Double number(JsonNode value) {
		return (value.isNumber() && Double.isFinite(value.doubleValue())) ? value.doubleValue() : null;
	}

}
