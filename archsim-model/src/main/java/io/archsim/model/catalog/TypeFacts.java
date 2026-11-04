package io.archsim.model.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.TechnologyProfile;

/** What the catalog knows about one component type. */
public final class TypeFacts {

	private final ComponentSpec<?> spec;

	private final Presentation presentation;

	private final List<Setting> settings;

	private final ObjectNode defaultConfig;

	TypeFacts(ComponentSpec<?> spec, Presentation presentation) {
		this.spec = spec;
		this.presentation = presentation;
		this.settings = FieldDescriptors.settings(spec.configType());
		this.defaultConfig = ComponentEntry.defaultConfig(spec);
	}

	/**
	 * What the editor calls the type, such as {@code Database}.
	 * @return its display name
	 */
	public String displayName() {
		return this.spec.displayName();
	}

	/**
	 * Its settings, in the order the inspector lists them.
	 * @return the settings
	 */
	public List<Setting> settings() {
		return this.settings;
	}

	/**
	 * The default of a setting, as the catalog's default config has it.
	 * @param key the setting's JSON name
	 * @return a copy of its default; JSON {@code null} when it defaults to nothing (the
	 * default config leaves such settings out)
	 */
	public JsonNode defaultValue(String key) {
		JsonNode value = this.defaultConfig.get(key);
		return (value != null) ? value.deepCopy() : JsonNodeFactory.instance.nullNode();
	}

	/**
	 * Its technology presets, in the order the editor offers them.
	 * @return the presets
	 */
	public List<Preset> presets() {
		return this.presentation.presets();
	}

	/**
	 * The value the editor suggests for a setting of a link drawn from the type.
	 * @param key the link setting's JSON name
	 * @return a copy of the suggested value; empty when the type suggests none
	 */
	public Optional<JsonNode> linkDefault(String key) {
		return Optional.ofNullable(this.presentation.linkDefaults().get(key))
			.filter((value) -> !value.isNull())
			.map(JsonNode::deepCopy);
	}

	/**
	 * What a technology fills in for a setting left empty ({@code auto}).
	 * @param key the setting's JSON name
	 * @param technology the technology
	 * @return its default as JSON, JSON {@code null} when it says it has none; empty when
	 * its profile does not name the setting, or the type does not know the technology
	 */
	public Optional<JsonNode> technologyDefault(String key, String technology) {
		@Nullable TechnologyProfile profile = this.spec.technologies().get(technology);
		if (profile == null || !profile.values().containsKey(key)) {
			return Optional.empty();
		}
		return Optional.of(TechnologyDefaults.json(profile.values().get(key)));
	}

	/**
	 * The documentation that states a technology's default for a setting.
	 * @param key the setting's JSON name
	 * @param technology the technology
	 * @return the documentation's link; empty when the default is illustrative
	 */
	public Optional<String> technologySource(String key, String technology) {
		return Optional.ofNullable(this.presentation.technologySources().getOrDefault(key, Map.of()).get(technology));
	}

}
