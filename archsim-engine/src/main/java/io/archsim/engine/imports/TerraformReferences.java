package io.archsim.engine.imports;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What each resource's configuration refers to, from a plan's {@code configuration}
 * section: by the resource's address (inside a module, prefixed with the module's), the
 * addresses each attribute's expression references.
 */
final class TerraformReferences {

	private final Map<String, Map<String, List<JsonNode>>> byAddress = new HashMap<>();

	private TerraformReferences() {
	}

	/**
	 * The references of a plan's resources.
	 * @param plan the plan
	 * @return their references
	 * @throws ImportShapeException in Python's words for a section of the wrong kind
	 */
	static TerraformReferences of(ObjectNode plan) {
		TerraformReferences references = new TerraformReferences();
		references.walk(PythonMappings.dict(plan.path("configuration"), "get").path("root_module"), "");
		return references;
	}

	/**
	 * What a resource's attribute references.
	 * @param address the resource's address, as written
	 * @param attribute the attribute
	 * @return the addresses its expression references, as written; none when it has no
	 * references
	 * @throws ImportShapeException for an address that is a list or a mapping
	 */
	List<JsonNode> referencedBy(JsonNode address, String attribute) {
		return this.byAddress.getOrDefault(PythonValues.key(address), Map.of()).getOrDefault(attribute, List.of());
	}

	/**
	 * Reads a module's resources, then its module calls'.
	 * @param value the module, {@code value or {}}
	 * @param prefix the addresses' prefix: empty for the root module
	 */
	private void walk(JsonNode value, String prefix) {
		ObjectNode module = PythonMappings.dict(value, "get");
		for (JsonNode resource : PythonValues.items(module.path("resources"))) {
			JsonNode address = PythonValues.item(resource, "address");
			String key = prefix.isEmpty() ? PythonValues.key(address) : prefix + PythonValues.str(address);
			Map<String, List<JsonNode>> references = new LinkedHashMap<>();
			PythonMappings.dict(resource.path("expressions"), "items").properties().forEach((expression) -> {
				JsonNode referenced = expression.getValue().path("references");
				if (expression.getValue().isObject() && PythonValues.truthy(referenced)) {
					references.put(expression.getKey(), PythonValues.items(referenced));
				}
			});
			this.byAddress.put(key, references);
		}
		PythonMappings.dict(module.path("module_calls"), "items")
			.properties()
			.forEach((call) -> walk(PythonMappings.mapping(call.getValue()).path("module"),
					prefix + "module." + call.getKey() + "."));
	}

}
