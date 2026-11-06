package io.archsim.engine.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings of a compose service that come as a list or a mapping: its labels, its
 * environment and what it depends on.
 */
final class ComposeSettings {

	private ComposeSettings() {
	}

	/**
	 * A service's labels, from a list of {@code key=value} or a mapping.
	 * @param service the service
	 * @return each label's text, by name
	 */
	static Map<String, String> labels(ObjectNode service) {
		JsonNode raw = service.path("labels");
		return isList(raw) ? pairs(raw) : PythonMappings.texts(raw);
	}

	/**
	 * A service's environment, from a list of {@code KEY=value} or a mapping, whose
	 * variables without a value are left out.
	 * @param service the service
	 * @return each variable's text, by name
	 */
	static Map<String, String> environment(ObjectNode service) {
		JsonNode raw = service.path("environment");
		if (isList(raw)) {
			return pairs(raw);
		}
		Map<String, String> texts = new LinkedHashMap<>();
		PythonMappings.dict(raw, "items").properties().forEach((entry) -> {
			if (!entry.getValue().isNull()) {
				texts.put(entry.getKey(), PythonValues.str(entry.getValue()));
			}
		});
		return texts;
	}

	/**
	 * The services a service depends on, from a list or a mapping's keys.
	 * @param service the service
	 * @return their names
	 */
	static List<String> dependencies(ObjectNode service) {
		JsonNode raw = service.path("depends_on");
		if (raw.isObject() && PythonValues.truthy(raw)) {
			return List.copyOf(raw.propertyNames());
		}
		return PythonValues.items(raw).stream().map(PythonValues::str).toList();
	}

	private static boolean isList(JsonNode raw) {
		return raw.isArray() && PythonValues.truthy(raw);
	}

	private static Map<String, String> pairs(JsonNode items) {
		Map<String, String> texts = new LinkedHashMap<>();
		for (JsonNode item : items) {
			String text = PythonValues.str(item);
			int equals = text.indexOf('=');
			if (equals >= 0) {
				texts.put(text.substring(0, equals), text.substring(equals + 1));
			}
			else {
				texts.put(text, "");
			}
		}
		return texts;
	}

}
