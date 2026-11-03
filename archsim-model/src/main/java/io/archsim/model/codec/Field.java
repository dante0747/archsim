package io.archsim.model.codec;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One field of a record: its JSON name, the snake_case name pydantic also accepts, how
 * its value maps to JSON, and its default.
 *
 * @param name the JSON name, the record component's name ({@code readRatio})
 * @param pythonName the Python attribute name, accepted too ({@code read_ratio})
 * @param shape how the value maps to JSON
 * @param accessor the record's accessor of the field
 * @param fieldDefault whether the input must have the field, and its value when the input
 * leaves it out
 * @param omitWhenDefault whether the written JSON leaves the field out while it has its
 * default
 * @param description the field's description in the JSON Schema
 * @param uiHints the field's {@link Ui} hints, the JSON Schema's {@code x-ui} object;
 * {@code null} when it has none
 */
record Field(String name, String pythonName, Shape shape, Method accessor, FieldDefault fieldDefault,
		boolean omitWhenDefault, @Nullable String description, @Nullable ObjectNode uiHints) {

	/**
	 * The field of a record component, from its type and its annotations.
	 * @param component the record component
	 * @return the field, with its shape and its default
	 */
	static Field of(RecordComponent component) {
		Shape shape = TypeShapes.of(component.getAnnotatedType());
		Description description = component.getAnnotation(Description.class);
		return new Field(component.getName(), snakeCase(component.getName()), shape,
				Reflection.accessible(component.getAccessor()), FieldDefault.of(component, shape),
				component.isAnnotationPresent(OmitWhenDefault.class),
				(description != null) ? description.value() : null, UiHints.of(component).orElse(null));
	}

	/**
	 * The key an input holds this field under: its JSON name, else its Python name.
	 * @param in the JSON object read
	 * @return the key, or {@code null} when the input has neither name
	 */
	@Nullable String keyIn(JsonNode in) {
		if (in.has(this.name)) {
			return this.name;
		}
		if (in.has(this.pythonName)) {
			return this.pythonName;
		}
		return null;
	}

	/**
	 * {@code readRatio} becomes {@code read_ratio}, as pydantic's alias generator undone.
	 * @param camel a camelCase name
	 * @return the snake_case name
	 */
	private static String snakeCase(String camel) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < camel.length(); i++) {
			char character = camel.charAt(i);
			if (Character.isUpperCase(character)) {
				out.append('_').append(Character.toLowerCase(character));
			}
			else {
				out.append(character);
			}
		}
		return out.toString();
	}

}
