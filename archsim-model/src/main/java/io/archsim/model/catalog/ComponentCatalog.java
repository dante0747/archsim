package io.archsim.model.catalog;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.EdgeConfig;
import io.archsim.model.document.Workload;

/**
 * Everything the editor renders its palette and inspector from: each component type with
 * its ports, settings, defaults, presets and technologies, then the link and workload
 * settings and the text of the "experimental" badge. Adding a component type needs no
 * change to the editor.
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
		catalog.put("tierNote", TIER_NOTE);
		return catalog;
	}

}
