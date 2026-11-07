package io.archsim.engine.assumptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.TechnologyProfile;

/**
 * One component type as the page reads it: its technologies from its specification, and
 * the rest from its entry in the catalog the editor reads (settings, presets, link
 * defaults, and each technology's defaults with the documentation behind them).
 *
 * @param spec the type's specification
 * @param entry the type's catalog entry
 */
record CatalogPart(ComponentSpec<?> spec, JsonNode entry) {

	/**
	 * Every type of a registry, in its order.
	 * @param registry the component types
	 * @param catalog the registry's catalog
	 * @return each type with its catalog entry
	 */
	static List<CatalogPart> all(ComponentRegistry registry, JsonNode catalog) {
		Map<String, JsonNode> entries = new LinkedHashMap<>();
		catalog.path("components").forEach((entry) -> entries.put(entry.path("type").stringValue(), entry));
		return registry.specs()
			.stream()
			.map((spec) -> new CatalogPart(spec,
					Objects.requireNonNull(entries.get(spec.type()), () -> "no catalog entry for type " + spec.type())))
			.toList();
	}

	String displayName() {
		return this.spec.displayName();
	}

	Map<String, TechnologyProfile> technologies() {
		return this.spec.technologies();
	}

	Map<String, JsonNode> fields() {
		return PageText.byKey(this.entry.path("fields"));
	}

	JsonNode presets() {
		return this.entry.path("presets");
	}

	JsonNode linkDefaults() {
		return this.entry.path("linkDefaults");
	}

	/**
	 * The default an "auto" setting takes with a technology.
	 * @param field the setting
	 * @param technology the technology
	 * @return its default; JSON {@code null} when the technology sets none
	 */
	JsonNode technologyDefault(String field, String technology) {
		return this.entry.path("technologyDefaults").path(field).path(technology);
	}

	/**
	 * The documentation behind a technology's default.
	 * @param field the setting
	 * @param technology the technology
	 * @return the link; {@code null} when the default is illustrative
	 */
	@Nullable String technologySource(String field, String technology) {
		JsonNode source = this.entry.path("technologySources").path(field).path(technology);
		return source.isString() ? source.stringValue() : null;
	}

}
