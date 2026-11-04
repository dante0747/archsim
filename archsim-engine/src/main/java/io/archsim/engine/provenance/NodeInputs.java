package io.archsim.engine.provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.catalog.Setting;
import io.archsim.model.catalog.TypeFacts;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * The inputs of one part: every setting marked as a performance assumption, and every
 * setting the document records provenance for, unless it does not apply to the part's
 * other settings.
 */
final class NodeInputs {

	private final ComponentRegistry registry;

	private final ComponentFacts facts;

	NodeInputs(ComponentRegistry registry, ComponentFacts facts) {
		this.registry = registry;
		this.facts = facts;
	}

	/**
	 * The part's inputs.
	 * @param document the design
	 * @param node the part
	 * @return its inputs, in the order its type lists its settings; none for a part of an
	 * unknown type or with a config that is not valid (validation reports those)
	 */
	List<Input> of(ArchitectureDocument document, NodeSpec node) {
		Optional<TypeFacts> type = this.facts.type(node.type());
		Optional<ObjectNode> parsed = this.registry.find(node.type()).flatMap((spec) -> parsed(spec, node));
		if (type.isEmpty() || parsed.isEmpty()) {
			return List.of();
		}
		PartSettings part = new PartSettings(node, type.get());
		List<Input> inputs = new ArrayList<>();
		for (Setting setting : type.get().settings()) {
			boolean recorded = document.provenance().containsKey(InputPaths.node(node.id(), setting.key()));
			if ((setting.assumption() || recorded) && applies(setting, part)) {
				inputs.add(new Input(InputScope.NODE, node.id(), node.name(), setting.key(), setting.label(),
						FieldText.of(setting, value(setting, parsed.get(), part)),
						NodeSources.of(document, part, setting.key())));
			}
		}
		return inputs;
	}

	/**
	 * The part's config, its defaults filled in, as JSON.
	 * @param spec the part's type
	 * @param node the part
	 * @return the parsed config; empty when it is not valid
	 */
	private static Optional<ObjectNode> parsed(ComponentSpec<?> spec, NodeSpec node) {
		try {
			return Optional.of(RecordCodec.write(spec.parseConfig(node.config())));
		}
		catch (BindingException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Whether a setting applies, given the settings it depends on (as written, or their
	 * defaults).
	 * @param setting the setting
	 * @param part the part's settings
	 * @return whether every setting it depends on has the value it needs
	 */
	private static boolean applies(Setting setting, PartSettings part) {
		for (Map.Entry<String, JsonNode> when : setting.visibleWhen().entrySet()) {
			if (!Values.equal(part.orDefault(when.getKey()), when.getValue())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The setting's value in effect: the parsed config's, or, for an empty setting, what
	 * the part's technology fills in.
	 * @param setting the setting
	 * @param parsed the parsed config, as JSON (a setting written only when it is not its
	 * default may be left out)
	 * @param part the part's settings
	 * @return the value
	 */
	private static JsonNode value(Setting setting, ObjectNode parsed, PartSettings part) {
		JsonNode value = parsed.has(setting.key()) ? parsed.get(setting.key()) : setting.defaultValue();
		if (!Values.isNone(value)) {
			return value;
		}
		return part.technology()
			.flatMap((technology) -> part.type().technologyDefault(setting.key(), technology))
			.filter((filled) -> !Values.isNone(filled))
			.orElse(value);
	}

}
