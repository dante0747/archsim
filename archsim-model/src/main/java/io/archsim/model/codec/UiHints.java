package io.archsim.model.codec;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a field's {@link Ui} hints as the {@code x-ui} JSON object the editor reads.
 */
public final class UiHints {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private UiHints() {
	}

	/**
	 * The {@code x-ui} object of a record component, with the attributes it sets.
	 * @param component a record component
	 * @return the hints, or empty when the component has no {@link Ui}
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
		String value = condition.substring(equals + 1);
		Class<?> type = Arrays.stream(record.getRecordComponents())
			.filter((component) -> component.getName().equals(field))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(record + " has no field " + field))
			.getType();
		boolean flag = type == boolean.class || type == Boolean.class;
		JsonNode typed = flag ? NODES.booleanNode(Boolean.parseBoolean(value)) : NODES.stringNode(value);
		return NODES.objectNode().set(field, typed);
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
