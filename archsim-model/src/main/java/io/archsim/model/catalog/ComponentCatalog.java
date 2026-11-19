package io.archsim.model.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeConfig;
import io.archsim.model.document.Workload;

/**
 * Everything the editor renders its palette and inspector from: each component type with
 * its ports, settings, defaults, presets and technologies, then the link, workload and
 * design-wide architecture settings and the text of the "experimental" badge. Adding a
 * component type needs no change to the editor.
 */
public final class ComponentCatalog {

	/** What the "experimental" badge says, everywhere it is shown. */
	public static final String TIER_NOTE = "Behaviour based on documentation and illustrative numbers; not yet "
			+ "validated against measurements.";

	private ComponentCatalog() {
	}

	/**
	 * The catalog of every type in a registry.
	 * @param registry the component types, in the order the palette lists them
	 * @return the catalog as {@code GET /components} serves it
	 * @throws IllegalStateException when a type has no presentation data on the classpath
	 */
	public static ObjectNode of(ComponentRegistry registry) {
		ObjectNode catalog = JsonNodeFactory.instance.objectNode();
		ArrayNode components = catalog.putArray("components");
		registry.specs().forEach((spec) -> components.add(ComponentEntry.of(spec, Presentation.of(spec.type()))));
		catalog.set("edgeFields", FieldDescriptors.of(EdgeConfig.class));
		catalog.set("workloadFields", FieldDescriptors.of(Workload.class));
		catalog.set("architectureFields", FieldDescriptors.ofHinted(Architecture.class));
		catalog.put("tierNote", TIER_NOTE);
		return catalog;
	}

	/**
	 * The behaviours each technology of a component type brings.
	 * @param type the component type's name, such as {@code service}
	 * @return the traits by technology, in the catalog's order; a technology that brings
	 * none is left out
	 * @throws IllegalStateException when the type has no presentation data on the
	 * classpath
	 */
	public static Map<String, List<TechnologyTrait>> technologyTraits(String type) {
		Map<String, List<TechnologyTrait>> traits = new LinkedHashMap<>();
		Presentation.of(type).technologyTraits().properties().forEach((technology) -> {
			List<TechnologyTrait> brought = new ArrayList<>();
			for (JsonNode trait : technology.getValue()) {
				brought.add(RecordCodec.read((ObjectNode) trait, TechnologyTrait.class));
			}
			traits.put(technology.getKey(), List.copyOf(brought));
		});
		return Collections.unmodifiableMap(traits);
	}

}
