package io.archsim.model.catalog;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.Port;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.document.RequestType;

/**
 * One component type's catalog entry: what the specification says about it, its settings,
 * and its presentation.
 */
final class ComponentEntry {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private ComponentEntry() {
	}

	/**
	 * The entry of a component type.
	 * @param spec the type's specification
	 * @param presentation what the editor shows about it beyond the specification
	 * @return the entry
	 */
	static ObjectNode of(ComponentSpec<?> spec, Presentation presentation) {
		Map<String, TechnologyProfile> technologies = spec.technologies();
		ObjectNode entry = NODES.objectNode();
		entry.put("type", spec.type());
		entry.put("displayName", spec.displayName());
		entry.put("category", spec.category().json());
		entry.put("description", presentation.description());
		entry.put("icon", presentation.icon());
		entry.put("isTrafficSource", spec.isTrafficSource());
		entry.set("ports", ports(spec.ports()));
		entry.set("fields", FieldDescriptors.of(spec.configType()));
		entry.set("defaultConfig", defaultConfig(spec));
		entry.set("modeled", strings(presentation.modeled()));
		entry.set("notModeled", strings(presentation.notModeled()));
		entry.set("presets", presets(presentation.presets(), technologies));
		entry.set("linkDefaults", presentation.linkDefaults().deepCopy());
		entry.set("technologyTraits", presentation.technologyTraits().deepCopy());
		entry.set("technologyDefaults", TechnologyDefaults.of(technologies));
		entry.set("technologySources", sources(presentation.technologySources()));
		entry.set("technologyTiers", TechnologyDefaults.tiers(technologies));
		return entry;
	}

	private static ArrayNode ports(List<Port> ports) {
		ArrayNode out = NODES.arrayNode();
		for (Port port : ports) {
			ObjectNode json = out.addObject();
			json.put("name", port.name());
			json.put("direction", port.direction().json());
			json.put("label", port.label());
			json.put("description", port.description());
			json.set("requestTypes", strings(port.requestTypes().stream().map(RequestType::json).toList()));
			json.put("minEdges", port.minEdges());
			json.set("maxEdges", (port.maxEdges() != null) ? NODES.numberNode(port.maxEdges()) : NODES.nullNode());
		}
		return out;
	}

	/**
	 * The presets, each with its technology's tier when the type knows the technology.
	 * @param presets the presets as the presentation lists them
	 * @param technologies the type's technologies' profiles, by JSON name
	 * @return the presets as JSON, with their tiers
	 */
	private static ArrayNode presets(List<Preset> presets, Map<String, TechnologyProfile> technologies) {
		ArrayNode out = NODES.arrayNode();
		for (Preset preset : presets) {
			ObjectNode json = RecordCodec.write(preset);
			@Nullable TechnologyProfile profile = technologies.get(preset.technology());
			if (profile != null) {
				json.put("tier", profile.tier().json());
			}
			out.add(json);
		}
		return out;
	}

	/**
	 * Field → technology → the documentation behind its default, as JSON.
	 * @param sources the links, by field and then technology
	 * @return the links as a JSON object
	 */
	private static ObjectNode sources(Map<String, Map<String, String>> sources) {
		ObjectNode out = NODES.objectNode();
		sources.forEach((field, links) -> {
			ObjectNode byTechnology = out.putObject(field);
			links.forEach(byTechnology::put);
		});
		return out;
	}

	/**
	 * A type's default config: the settings that have a value when none is written.
	 * @param spec the type's specification
	 * @return the default config, without the settings that default to nothing
	 */
	static ObjectNode defaultConfig(ComponentSpec<?> spec) {
		return withoutNulls(RecordCodec.write(spec.parseConfig(NODES.objectNode())));
	}

	private static ArrayNode strings(List<String> values) {
		ArrayNode out = NODES.arrayNode();
		values.forEach(out::add);
		return out;
	}

	/**
	 * The object without its {@code null} fields, in nested objects and arrays too: the
	 * default config lists only the settings that have a value.
	 * @param object a JSON object
	 * @return a copy without the {@code null} fields
	 */
	private static ObjectNode withoutNulls(ObjectNode object) {
		ObjectNode out = NODES.objectNode();
		object.properties().forEach((property) -> {
			if (!property.getValue().isNull()) {
				out.set(property.getKey(), withoutNullsIn(property.getValue()));
			}
		});
		return out;
	}

	/**
	 * A value without the {@code null} fields of the objects in it.
	 * @param value a JSON value
	 * @return a copy without them, or the value itself when it holds no object
	 */
	private static JsonNode withoutNullsIn(JsonNode value) {
		if (value instanceof ObjectNode object) {
			return withoutNulls(object);
		}
		if (value instanceof ArrayNode array) {
			ArrayNode out = NODES.arrayNode();
			array.forEach((item) -> out.add(withoutNullsIn(item)));
			return out;
		}
		return value;
	}

}
