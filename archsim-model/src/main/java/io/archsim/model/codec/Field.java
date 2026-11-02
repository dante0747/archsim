package io.archsim.model.codec;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One field of a record: its JSON name, the snake_case name pydantic also accepts, how
 * its value maps to JSON, and its default.
 *
 * @param name the JSON name, the record component's name ({@code readRatio})
 * @param pythonName the Python attribute name, accepted too ({@code read_ratio})
 * @param shape how the value maps to JSON
 * @param accessor the record's accessor of the field
 * @param required whether the input must have the field
 * @param defaultValue the value when the input leaves the field out
 * @param defaultInSchema whether the JSON Schema shows the default (not for objects and
 * arrays)
 * @param omitWhenDefault whether the written JSON leaves the field out while it has its
 * default
 * @param description the field's description in the JSON Schema
 * @param extra more JSON Schema for the field
 */
record Field(String name, String pythonName, Shape shape, Method accessor, boolean required,
		@Nullable Object defaultValue, boolean defaultInSchema, boolean omitWhenDefault, @Nullable String description,
		@Nullable ObjectNode extra) {

	/**
	 * Room for a few underscores, so that the snake_case name seldom outgrows its buffer.
	 */
	private static final int UNDERSCORE_ROOM = 4;

	/**
	 * The field of a record component, from its type and its annotations.
	 * @param component the record component
	 * @return the field, with its shape and its default
	 */
	static Field of(RecordComponent component) {
		Shape shape = Shapes.of(component.getAnnotatedType());
		FieldDefault fieldDefault = FieldDefault.of(component, shape);
		Description description = component.getAnnotation(Description.class);
		ObjectNode extra = UiHints.of(component)
			.map((hints) -> JsonNodeFactory.instance.objectNode().set("x-ui", hints))
			.orElse(null);
		return new Field(component.getName(), snakeCase(component.getName()), shape,
				Reflection.accessible(component.getAccessor()), fieldDefault.required(), fieldDefault.value(),
				fieldDefault.inSchema(), component.isAnnotationPresent(OmitWhenDefault.class),
				(description != null) ? description.value() : null, extra);
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
		StringBuilder out = new StringBuilder(camel.length() + UNDERSCORE_ROOM);
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
