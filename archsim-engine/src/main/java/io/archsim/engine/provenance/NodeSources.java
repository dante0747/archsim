package io.archsim.engine.provenance;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.model.catalog.Preset;
import io.archsim.model.catalog.TypeFacts;
import io.archsim.model.document.ArchitectureDocument;

/**
 * Where the value of one setting of a part came from: measured when the document records
 * it and it still has that value; documented when it is what the part's technology (or a
 * preset of it) fills in and its documentation states it; illustrative when it is the
 * default, or another number a profile or preset fills in; assumed otherwise.
 */
final class NodeSources {

	/** The value of a setting left for the technology to fill in. */
	private static final String AUTO = "auto";

	private NodeSources() {
	}

	/**
	 * The provenance of a setting.
	 * @param document the design
	 * @param part the part's settings
	 * @param key the setting's JSON name
	 * @return where its value came from
	 */
	static Provenance of(ArchitectureDocument document, PartSettings part, String key) {
		JsonNode current = part.written(key);
		return Recorded.of(document.provenance().get(InputPaths.node(part.id(), key)), current)
			.orClassify(() -> classify(part, key, current));
	}

	/**
	 * Where a setting's value came from, the document recording nothing that holds.
	 * @param part the part's settings
	 * @param key the setting's JSON name
	 * @param current its value as written
	 * @return documented by the technology, else the default, else a preset, else assumed
	 */
	private static Provenance classify(PartSettings part, String key, JsonNode current) {
		Optional<Provenance> documented = part.technology()
			.flatMap((technology) -> byTechnology(key, current, technology, part.type()));
		if (documented.isPresent()) {
			return documented.get();
		}
		if (!part.has(key) || Values.equal(current, part.type().defaultValue(key))) {
			return Provenance.archSimDefault();
		}
		return part.technology()
			.flatMap((technology) -> byPreset(key, current, technology, part.type()))
			.orElseGet(Provenance::assumed);
	}

	/**
	 * Documented by the technology: the setting is empty ({@code auto}), or set to the
	 * number the technology fills in, and the technology's documentation states it.
	 * @param key the setting's JSON name
	 * @param current its value as written
	 * @param technology the part's technology
	 * @param type the part's type
	 * @return the provenance; empty when the technology does not document it
	 */
	private static Optional<Provenance> byTechnology(String key, JsonNode current, String technology, TypeFacts type) {
		Optional<JsonNode> filled = type.technologyDefault(key, technology);
		if (filled.isEmpty() || !(isLeftEmpty(current) || Values.equal(current, filled.get()))) {
			return Optional.empty();
		}
		return type.technologySource(key, technology)
			.map((source) -> Provenance.documented(source,
					"default for " + technology + ", stated in its documentation"));
	}

	/**
	 * Whether a setting is left for the technology to fill in.
	 * @param current its value as written
	 * @return whether it is not set, or set to {@code auto}
	 */
	private static boolean isLeftEmpty(JsonNode current) {
		if (Values.isNone(current)) {
			return true;
		}
		return current.isString() && AUTO.equals(current.stringValue());
	}

	/**
	 * The first preset of the part's technology that sets the setting to its value.
	 * @param key the setting's JSON name
	 * @param value its value as written
	 * @param technology the part's technology
	 * @param type the part's type
	 * @return documented when the preset cites its documentation, else illustrative;
	 * empty when no preset sets it so
	 */
	private static Optional<Provenance> byPreset(String key, JsonNode value, String technology, TypeFacts type) {
		for (Preset preset : type.presets()) {
			boolean setSo = preset.setting(key).filter((set) -> Values.equal(set, value)).isPresent();
			if (preset.technology().equals(technology) && setSo) {
				return Optional.of(preset.source(key)
					.map((source) -> Provenance.documented(source,
							"preset " + preset.name() + ", stated in its documentation"))
					.orElseGet(() -> Provenance.illustrative("preset " + preset.name())));
			}
		}
		return Optional.empty();
	}

}
