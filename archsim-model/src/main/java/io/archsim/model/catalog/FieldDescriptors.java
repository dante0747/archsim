package io.archsim.model.catalog;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Lt;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.codec.UiHints;

/**
 * Describes a config record's settings for the editor's forms: for each field its JSON
 * name, label, kind, whether it may be empty, its description, default and bounds, and
 * the rest of its {@code @Ui} hints (unit, group, when it is shown, ...).
 */
final class FieldDescriptors {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The {@code @Ui} hint of a field's label. */
	private static final String LABEL = "label";

	/** The {@code @Ui} hint of a field's kind of input. */
	private static final String KIND = "kind";

	/** The {@code @Ui} hint of a performance assumption. */
	private static final String ASSUMPTION = "assumption";

	/** The {@code @Ui} hint of a field the inspector shows first. */
	private static final String HEADLINE = "headline";

	/**
	 * A bound beyond this is written as a decimal; inside it, a whole bound as an
	 * integer.
	 */
	private static final double LARGEST_EXACT_WHOLE = 1e15;

	private FieldDescriptors() {
	}

	/**
	 * One descriptor per field of a config record, in declaration order.
	 * @param config the config record
	 * @return the descriptors
	 */
	static ArrayNode of(Class<? extends Record> config) {
		ObjectNode defaults = defaults(config);
		ArrayNode fields = NODES.arrayNode();
		for (RecordComponent component : config.getRecordComponents()) {
			fields.add(describe(component, defaults));
		}
		return fields;
	}

	/**
	 * One setting per field of a config record, in declaration order: what the analysis
	 * reads of each descriptor, from the same facts.
	 * @param config the config record
	 * @return the settings
	 */
	static List<Setting> settings(Class<? extends Record> config) {
		ObjectNode defaults = defaults(config);
		List<Setting> settings = new ArrayList<>();
		for (RecordComponent component : config.getRecordComponents()) {
			settings.add(setting(component, defaults));
		}
		return List.copyOf(settings);
	}

	/**
	 * A field's name made readable, for a field without a label: {@code maxAttempts}
	 * becomes {@code Max attempts}.
	 * @param name a field's JSON name
	 * @return the label
	 */
	static String humanize(String name) {
		StringBuilder spaced = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char character = name.charAt(i);
			if (Character.isUpperCase(character) && i > 0) {
				spaced.append(' ');
			}
			spaced.append(character);
		}
		String words = spaced.toString().replace('_', ' ');
		return words.isEmpty() ? words
				: words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1).toLowerCase(Locale.ROOT);
	}

	private static ObjectNode describe(RecordComponent component, ObjectNode defaults) {
		ObjectNode hints = hints(component);
		FieldKind kind = kind(component, hints);
		Description description = component.getAnnotation(Description.class);
		ObjectNode field = NODES.objectNode();
		field.put("key", component.getName());
		field.put("label", label(component, hints));
		field.put("kind", kind.name());
		field.put("nullable", kind.nullable());
		field.put("description", (description != null) ? description.value() : "");
		field.set("default", defaultOf(component, defaults));
		field.put("assumption", flag(hints, ASSUMPTION));
		field.put("headline", flag(hints, HEADLINE));
		ArrayNode options = kind.options();
		if (options != null) {
			field.set("options", options.deepCopy());
		}
		putBounds(component.getAnnotatedType(), field);
		hints.remove(List.of(LABEL, KIND, ASSUMPTION, HEADLINE));
		field.setAll(hints);
		return field;
	}

	private static Setting setting(RecordComponent component, ObjectNode defaults) {
		ObjectNode hints = hints(component);
		JsonNode unit = hints.path("unit");
		Map<String, JsonNode> visibleWhen = new LinkedHashMap<>();
		hints.path("visibleWhen").properties().forEach((when) -> visibleWhen.put(when.getKey(), when.getValue()));
		return new Setting(component.getName(), label(component, hints), unit.isString() ? unit.stringValue() : null,
				kind(component, hints).name(), flag(hints, ASSUMPTION), visibleWhen, defaultOf(component, defaults));
	}

	/**
	 * The record's defaults, as JSON.
	 * @param config the config record
	 * @return the config the record reads from an empty object, written back
	 */
	private static ObjectNode defaults(Class<? extends Record> config) {
		return RecordCodec.write(RecordCodec.read(NODES.objectNode(), config));
	}

	private static ObjectNode hints(RecordComponent component) {
		return UiHints.of(component).orElseGet(NODES::objectNode);
	}

	private static String label(RecordComponent component, ObjectNode hints) {
		JsonNode label = hints.get(LABEL);
		return (label != null) ? label.stringValue() : humanize(component.getName());
	}

	/**
	 * The field's kind of input: from its type, or as its {@code @Ui} names it.
	 * @param component the field
	 * @param hints its {@code @Ui} hints
	 * @return the kind
	 */
	private static FieldKind kind(RecordComponent component, ObjectNode hints) {
		FieldKind kind = FieldKind.of(component.getAnnotatedType());
		JsonNode hinted = hints.get(KIND);
		return (hinted != null) ? kind.hintedAs(hinted.stringValue()) : kind;
	}

	private static boolean flag(ObjectNode hints, String name) {
		JsonNode flag = hints.get(name);
		return flag != null && flag.booleanValue();
	}

	/**
	 * The field's default as JSON; an empty list for a list the JSON leaves out when
	 * empty.
	 * @param component the field
	 * @param defaults the record's defaults as JSON
	 * @return the field's default
	 */
	private static JsonNode defaultOf(RecordComponent component, ObjectNode defaults) {
		JsonNode value = defaults.get(component.getName());
		if ((value == null || value.isNull()) && component.getType() == List.class) {
			return NODES.arrayNode();
		}
		return (value != null) ? value : NODES.nullNode();
	}

	/**
	 * Adds {@code min}, {@code exclusiveMin} and {@code max}, from the field's bounds.
	 * @param type the field's annotated type, which carries its bounds
	 * @param field the descriptor to add them to
	 */
	private static void putBounds(AnnotatedType type, ObjectNode field) {
		Ge ge = type.getAnnotation(Ge.class);
		Gt gt = type.getAnnotation(Gt.class);
		Le le = type.getAnnotation(Le.class);
		Lt lt = type.getAnnotation(Lt.class);
		if (ge != null) {
			field.set("min", number(ge.value()));
		}
		else if (gt != null) {
			field.set("min", number(gt.value()));
			field.put("exclusiveMin", true);
		}
		if (le != null) {
			field.set("max", number(le.value()));
		}
		else if (lt != null) {
			field.set("max", number(lt.value()));
		}
	}

	/**
	 * A bound as the Python models write it: a whole number as an integer.
	 * @param value the bound
	 * @return the bound as JSON
	 */
	private static JsonNode number(double value) {
		boolean whole = value == Math.rint(value) && Math.abs(value) < LARGEST_EXACT_WHOLE;
		return whole ? NODES.numberNode((long) value) : NODES.numberNode(value);
	}

}
