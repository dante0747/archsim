package io.archsim.model.codec;

import java.lang.reflect.RecordComponent;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What a field takes when the input leaves it out, from its {@link Default} or
 * {@link DefaultFrom}; a nullable field without one defaults to {@code null}, any other
 * is required.
 *
 * @param required whether the input must have the field
 * @param value the default, when it has one
 * @param inSchema whether the JSON Schema shows the default
 */
record FieldDefault(boolean required, @Nullable Object value, boolean inSchema) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The default of a record component.
	 * @param component the record component
	 * @param shape the shape of the component's value, which reads a {@link Default}
	 * @return the default, or that the field is required
	 */
	static FieldDefault of(RecordComponent component, Shape shape) {
		Default declared = component.getAnnotation(Default.class);
		if (declared != null) {
			return declared(declared, component, shape);
		}
		DefaultFrom constant = component.getAnnotation(DefaultFrom.class);
		if (constant != null) {
			return new FieldDefault(false, valueOf(constant, component), false);
		}
		boolean nullable = shape instanceof NullableShape;
		return new FieldDefault(!nullable, null, nullable);
	}

	/**
	 * A {@link Default}, bound like an input; the JSON Schema shows it unless it is a
	 * collection.
	 * @param declared the annotation
	 * @param component the record component it is on
	 * @param shape the shape that binds it
	 * @return the default
	 */
	private static FieldDefault declared(Default declared, RecordComponent component, Shape shape) {
		JsonNode json = defaultJson(declared.value(), TypeShapes.raw(component.getAnnotatedType()));
		Errors errors = new Errors();
		Object value = shape.read(json, Location.ROOT, errors);
		if (!errors.isEmpty()) {
			throw new IllegalStateException("the default of " + component + " does not fit it: " + errors.list());
		}
		return new FieldDefault(false, value, !json.isContainer());
	}

	/**
	 * The value of a {@link DefaultFrom} constant.
	 * @param constant the annotation
	 * @param component the record component it is on
	 * @return the constant's value
	 * @throws IllegalStateException when the record has no static constant of that name,
	 * or it holds no value of the component's type
	 */
	private static Object valueOf(DefaultFrom constant, RecordComponent component) {
		Object value = Reflection.staticValue(component.getDeclaringRecord(), constant.value())
			.orElseThrow(() -> new IllegalStateException(
					"the default of " + component + " names no static constant " + constant.value()));
		if (!component.getType().isInstance(value)) {
			throw new IllegalStateException("the default of " + component + " is not one: " + value);
		}
		return value;
	}

	/**
	 * A default as JSON: the string itself for a string or an enum, else the annotation's
	 * JSON.
	 * @param value the annotation's text
	 * @param type the field's class
	 * @return the JSON to bind
	 */
	private static JsonNode defaultJson(String value, Class<?> type) {
		if (type == String.class || type.isEnum()) {
			return JsonNodeFactory.instance.stringNode(value);
		}
		return JSON.readTree(value);
	}

}
