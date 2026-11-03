package io.archsim.model.catalog;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.codec.Choice;
import io.archsim.model.codec.JsonEnum;
import io.archsim.model.codec.Tagged;
import io.archsim.model.document.RequestType;

/**
 * The form control a setting's type calls for: {@code boolean}, {@code integer},
 * {@code number}, {@code string}, {@code enum} (with its options), {@code requestTypes},
 * {@code distribution} or {@code object}.
 *
 * @param name the kind
 * @param nullable whether the setting may be empty
 * @param options the values an {@code enum} or {@code requestTypes} setting may take
 */
record FieldKind(String name, boolean nullable, @Nullable ArrayNode options) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final Map<Class<?>, String> PLAIN = Map.of(boolean.class, "boolean", Boolean.class, "boolean",
			int.class, "integer", Integer.class, "integer", long.class, "integer", Long.class, "integer", double.class,
			"number", Double.class, "number", String.class, "string");

	/**
	 * The kind of a record component's type.
	 * @param type the component's annotated type
	 * @return its kind
	 */
	static FieldKind of(AnnotatedType type) {
		boolean nullable = type.isAnnotationPresent(Nullable.class);
		Class<?> raw = raw(type.getType());
		Choice[] choices = type.getAnnotationsByType(Choice.class);
		if (choices.length > 0) {
			ArrayNode values = NODES.arrayNode();
			Arrays.stream(choices).map(Choice::value).forEach(values::add);
			return new FieldKind("enum", nullable, values);
		}
		if (raw.isEnum() && JsonEnum.class.isAssignableFrom(raw)) {
			return new FieldKind("enum", nullable, jsonNames(raw.getEnumConstants()));
		}
		if (raw.isAnnotationPresent(Tagged.class)) {
			return new FieldKind("distribution", nullable, null);
		}
		if (raw == List.class) {
			return new FieldKind("requestTypes", nullable, jsonNames(RequestType.values()));
		}
		return new FieldKind(PLAIN.getOrDefault(raw, "object"), nullable, null);
	}

	/**
	 * This kind as the editor's hint names it instead. A list shown as another kind has
	 * no request types to offer.
	 * @param hinted the kind the setting's {@code @Ui} names
	 * @return the hinted kind
	 */
	FieldKind hintedAs(String hinted) {
		return new FieldKind(hinted, this.nullable, hinted.equals(this.name) ? this.options : null);
	}

	private static ArrayNode jsonNames(Object[] constants) {
		ArrayNode names = NODES.arrayNode();
		Arrays.stream(constants).map((constant) -> ((JsonEnum) constant).json()).forEach(names::add);
		return names;
	}

	private static Class<?> raw(Type type) {
		if (type instanceof Class<?> plain) {
			return plain;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> plain) {
			return plain;
		}
		throw new IllegalArgumentException("a config setting cannot have the type " + type);
	}

}
