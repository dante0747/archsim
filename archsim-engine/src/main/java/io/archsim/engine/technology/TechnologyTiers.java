package io.archsim.engine.technology;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.document.NodeSpec;

/**
 * Each part's technology and its tier, for the parts whose type has technology profiles.
 */
public final class TechnologyTiers {

	private final ComponentRegistry registry;

	/**
	 * Reads the technologies of designs made of a registry's types.
	 * @param registry the component types
	 */
	public TechnologyTiers(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * The technology and tier of each part that has one.
	 * @param nodes the design's parts
	 * @return their technologies, in the parts' order; parts of an unknown type or with a
	 * config that is not valid are left out (validation reports them)
	 */
	public List<PartTechnology> of(List<NodeSpec> nodes) {
		List<PartTechnology> found = new ArrayList<>();
		for (NodeSpec node : nodes) {
			this.registry.find(node.type())
				.filter((spec) -> !spec.technologies().isEmpty())
				.flatMap((spec) -> technologyOf(spec, node))
				.ifPresent(found::add);
		}
		return List.copyOf(found);
	}

	/**
	 * The part's technology, when its type has a profile of it.
	 * @param spec the part's type
	 * @param node the part
	 * @return the part's technology; empty when its config is not valid
	 */
	private static Optional<PartTechnology> technologyOf(ComponentSpec<?> spec, NodeSpec node) {
		JsonNode technology;
		try {
			technology = RecordCodec.write(spec.parseConfig(node.config())).path("technology");
		}
		catch (BindingException ex) {
			return Optional.empty();
		}
		if (!technology.isString()) {
			return Optional.empty();
		}
		String name = technology.stringValue();
		@Nullable TechnologyProfile profile = spec.technologies().get(name);
		return Optional.ofNullable(profile)
			.map((known) -> new PartTechnology(node.id(), node.name(), spec.type(), name, known.tier()));
	}

}
