package io.archsim.model.codec;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a field's {@link Ui} hints as the {@code x-ui} JSON object the editor reads.
 */
public final class UiHints {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The types of field a condition's value is a JSON boolean for. */
	private static final Set<Class<?>> BOOLEANS = Set.of(boolean.class, Boolean.class);

	/** The types of field a condition's value is a JSON integer for. */
	private static final Set<Class<?>> INTEGERS = Set.of(int.class, Integer.class, long.class, Long.class);

	/** The types of field a condition's value is a JSON float for. */
	private static final Set<Class<?>> FLOATS = Set.of(double.class, Double.class);

	private UiHints() {
	}

	/**
	 * The {@code x-ui} object of a record component, with the attributes it sets.
	 * @param component a record component
	 * @return the hints, or empty when the component has no {@link Ui}
	 * @throws IllegalArgumentException when its {@link Ui#visibleWhen()} is not a
	 * condition on a field of the record that a condition can test
	 */
	public static Optional<ObjectNode> of(RecordComponent component) {
		Ui ui = component.getAnnotation(Ui.class);
		if (ui == null) {
			return Optional.empty();
		}
		ObjectNode hints = NODES.objectNode();
		putText(hints, "label", ui.label());
		putText(hints, "unit", ui.unit());
		putText(hints, "group", ui.group());
		putFlag(hints, "assumption", ui.assumption());
		putFlag(hints, "headline", ui.headline());
		if (!ui.visibleWhen().isEmpty()) {
			hints.set("visibleWhen", condition(component.getDeclaringRecord(), ui.visibleWhen()));
		}
		if (ui.step() != 0) {
			hints.put("step", ui.step());
		}
		putText(hints, "kind", ui.kind());
		putText(hints, "refType", ui.refType());
		putFlag(hints, "hidden", ui.hidden());
		return Optional.of(hints);
	}

	/**
	 * {@code field=value} as {@code {"field": value}}, the value typed like the field.
	 * @param record the record the field is in
	 * @param condition the {@link Ui#visibleWhen()} text
	 * @return the condition as the editor reads it
	 */
	private static ObjectNode condition(Class<?> record, String condition) {
		int equals = condition.indexOf('=');
		if (equals <= 0) {
			throw new IllegalArgumentException("visibleWhen is field=value: " + condition);
		}
		String field = condition.substring(0, equals);
		JsonNode value = typedValue(field, fieldType(record, field), condition.substring(equals + 1));
		return NODES.objectNode().set(field, value);
	}

	private static Class<?> fieldType(Class<?> record, String field) {
		return Arrays.stream(record.getRecordComponents())
			.filter((component) -> component.getName().equals(field))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(record + " has no field " + field))
			.getType();
	}

	/**
	 * A condition's value as the JSON of the field it tests.
	 * @param field the name of the field the condition tests
	 * @param type the field's class
	 * @param value the value's text
	 * @return a boolean, a number or a string, as the field's own values are written
	 * @throws IllegalArgumentException when no condition can test a field of this type,
	 * or the value is not a number the field can hold
	 */
	private static JsonNode typedValue(String field, Class<?> type, String value) {
		if (BOOLEANS.contains(type)) {
			if (!"true".equals(value) && !"false".equals(value)) {
				throw new IllegalArgumentException(
						"visibleWhen tests %s, a boolean, against '%s': write true or false".formatted(field, value));
			}
			return NODES.booleanNode(Boolean.parseBoolean(value));
		}
		if (INTEGERS.contains(type)) {
			return NODES.numberNode(Long.parseLong(value));
		}
		if (FLOATS.contains(type)) {
			return NODES.numberNode(Double.parseDouble(value));
		}
		if (JsonEnum.class.isAssignableFrom(type)) {
			return NODES.stringNode(choice(field, type, value));
		}
		if (type == String.class) {
			return NODES.stringNode(value);
		}
		throw new IllegalArgumentException(
				"visibleWhen cannot test %s, a %s: only a boolean, a number, a String or a JsonEnum field"
					.formatted(field, type.getSimpleName()));
	}

	/**
	 * A condition's value for a choice field, which must be one of the field's choices.
	 * @param field the name of the field the condition tests
	 * @param type the field's enum class
	 * @param value the value's text
	 * @return the value
	 * @throws IllegalArgumentException when the value is not one of the choices
	 */
	private static String choice(String field, Class<?> type, String value) {
		List<String> choices = Arrays.stream(type.getEnumConstants())
			.map((constant) -> ((JsonEnum) constant).json())
			.toList();
		if (!choices.contains(value)) {
			throw new IllegalArgumentException(
					"visibleWhen tests %s against '%s', which is not one of %s".formatted(field, value, choices));
		}
		return value;
	}

	private static void putText(ObjectNode hints, String name, String value) {
		if (!value.isEmpty()) {
			hints.put(name, value);
		}
	}

	private static void putFlag(ObjectNode hints, String name, boolean value) {
		if (value) {
			hints.put(name, true);
		}
	}

}
