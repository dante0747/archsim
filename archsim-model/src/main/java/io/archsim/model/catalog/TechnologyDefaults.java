package io.archsim.model.catalog;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.TechnologyProfile;

/**
 * What a component type's technologies do by default, as the inspector shows it beside
 * each "auto" setting, and how well ArchSim knows each technology.
 */
final class TechnologyDefaults {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private TechnologyDefaults() {
	}

	/**
	 * Field → technology → the default an "auto" setting takes there.
	 * @param technologies the type's technologies' profiles, by JSON name
	 * @return the defaults, by field and then technology
	 */
	static ObjectNode of(Map<String, TechnologyProfile> technologies) {
		ObjectNode byField = NODES.objectNode();
		technologies.forEach((technology, profile) -> profile.values()
			.forEach((field, value) -> fieldOf(byField, field).set(technology, json(value))));
		return byField;
	}

	/**
	 * Technology → its tier.
	 * @param technologies the type's technologies' profiles, by JSON name
	 * @return each technology's tier
	 */
	static ObjectNode tiers(Map<String, TechnologyProfile> technologies) {
		ObjectNode tiers = NODES.objectNode();
		technologies.forEach((technology, profile) -> tiers.put(technology, profile.tier().json()));
		return tiers;
	}

	private static ObjectNode fieldOf(ObjectNode byField, String field) {
		JsonNode existing = byField.get(field);
		return (existing instanceof ObjectNode object) ? object : byField.putObject(field);
	}

	/**
	 * A default as JSON: a string, a number, a distribution or {@code null}.
	 * @param value a technology's default
	 * @return the default as JSON
	 */
	private static JsonNode json(@Nullable Object value) {
		return switch (value) {
			case null -> NODES.nullNode();
			case String text -> NODES.stringNode(text);
			case Integer number -> NODES.numberNode(number);
			case Double number -> NODES.numberNode(number);
			case Record distribution -> RecordCodec.write(distribution);
			default -> throw new IllegalArgumentException("not a technology default: " + value);
		};
	}

}
