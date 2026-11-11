package io.archsim.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.component.standard.StandardComponents;

/**
 * The tools that read what ArchSim offers: {@code list_part_types},
 * {@code list_templates} and {@code get_template}, as the Python server answered them.
 */
final class CatalogTools {

	private final Templates templates;

	/**
	 * The tools, over a folder of templates.
	 * @param templates the templates
	 */
	CatalogTools(Templates templates) {
		this.templates = templates;
	}

	/**
	 * {@code list_part_types}: every kind of part with its presets; or one kind's
	 * settings, ports and presets.
	 * @param arguments {@code type}, if any
	 * @return the kinds, or the one kind
	 * @throws ToolRefusal when no kind has that type
	 */
	JsonNode partTypes(Arguments arguments) {
		JsonNode catalog = ComponentCatalog.of(StandardComponents.registry()).get("components");
		String type = arguments.text("type");
		if (type == null || type.isEmpty()) {
			ArrayNode kinds = JsonNodeFactory.instance.arrayNode();
			catalog.forEach((kind) -> kinds.add(JsonNodeFactory.instance.objectNode()
				.put("type", kind.path("type").stringValue())
				.put("name", kind.path("displayName").stringValue())
				.set("category", JsonPicks.value(kind.path("category")))
				.set("description", JsonPicks.value(kind.path("description")))
				.set("presets", JsonPicks.pickEach(kind.path("presets"), "id", "name", "technology", "tier"))));
			return kinds;
		}
		JsonNode kind = catalog.valueStream()
			.filter((candidate) -> type.equals(candidate.path("type").stringValue()))
			.findFirst()
			.orElseThrow(() -> new ToolRefusal("unknown part type '" + type + "'; known: " + String.join(", ",
					catalog.valueStream().map((candidate) -> candidate.path("type").stringValue()).toList())));
		return JsonNodeFactory.instance.objectNode()
			.put("type", kind.path("type").stringValue())
			.put("name", kind.path("displayName").stringValue())
			.put("description", kind.path("description").stringValue())
			.set("ports", JsonPicks.pickEach(kind.path("ports"), "name", "direction", "requestTypes", "description"))
			.set("settings",
					JsonPicks.pickEach(kind.path("fields"), "key", "label", "kind", "default", "options", "unit", "min",
							"max", "nullable", "description"))
			.set("presets",
					JsonPicks.pickEach(kind.path("presets"), "id", "name", "technology", "tier", "description"));
	}

	/**
	 * {@code list_templates}: each ready-made design's id, name, description and number
	 * of scenarios.
	 * @param arguments none
	 * @return the templates
	 */
	JsonNode templates(Arguments arguments) {
		ArrayNode listed = JsonNodeFactory.instance.arrayNode();
		this.templates.all()
			.forEach((id, document) -> listed.addObject()
				.put("id", id)
				.set("name", JsonPicks.value(document.path("metadata").path("name")))
				.set("description", JsonPicks.value(document.path("metadata").path("description")))
				.put("scenarios", document.path("scenarios").size()));
		return listed;
	}

	/**
	 * {@code get_template}: a ready-made design's document.
	 * @param arguments {@code id}
	 * @return the document
	 * @throws ToolRefusal when no template has that id
	 */
	JsonNode template(Arguments arguments) {
		return this.templates.named(arguments.required("id"));
	}

}
