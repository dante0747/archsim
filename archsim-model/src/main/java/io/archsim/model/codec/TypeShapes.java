package io.archsim.model.codec;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the shape of an annotated Java type, its {@code @Nullable} aside: a string, a
 * number, a boolean, a {@link JsonEnum}, a list or a map of these, or any JSON. The
 * shapes of records and unions come from {@link Shapes}.
 */
final class TypeShapes {

	private TypeShapes() {
	}

	/**
	 * The shape of an annotated type, as though it were not {@code @Nullable}.
	 * @param annotated a field's or an item's type, with its annotations
	 * @return the shape that reads and writes its values
	 */
	static Shape of(AnnotatedType annotated) {
		Class<?> raw = raw(annotated);
		if (raw == String.class) {
			return StringShape.of(annotated);
		}
		if (raw == double.class || raw == Double.class) {
			return new FloatShape(Bounds.of(annotated));
		}
		if (isInteger(raw)) {
			return integer(annotated, raw);
		}
		if (raw == boolean.class || raw == Boolean.class) {
			return new BooleanShape();
		}
		if (raw.isEnum() && JsonEnum.class.isAssignableFrom(raw)) {
			return new EnumShape(raw);
		}
		return structured(annotated, raw);
	}

	/**
	 * The class of an annotated type without its type arguments.
	 * @param annotated a class or a parameterized type, such as {@code List<String>}
	 * @return the class, such as {@code List}
	 */
	static Class<?> raw(AnnotatedType annotated) {
		if (annotated.getType() instanceof Class<?> type) {
			return type;
		}
		if (annotated instanceof AnnotatedParameterizedType parameterized
				&& parameterized.getType() instanceof ParameterizedType type
				&& type.getRawType() instanceof Class<?> raw) {
			return raw;
		}
		throw new IllegalArgumentException("unsupported field type: " + annotated);
	}

	private static boolean isInteger(Class<?> raw) {
		return raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class;
	}

	/**
	 * A Python {@code int}, or a {@code Literal} of integers when it has {@link Choice}.
	 * @param annotated the integer type, with its annotations
	 * @param raw the integer class
	 * @return the integer's shape
	 */
	private static Shape integer(AnnotatedType annotated, Class<?> raw) {
		Choice[] choices = annotated.getAnnotationsByType(Choice.class);
		if (choices.length > 0) {
			return IntChoiceShape.of(choices);
		}
		return new IntegerShape(Bounds.of(annotated), raw == long.class || raw == Long.class);
	}

	/**
	 * A list, a map or any JSON; any other type is a record or a union.
	 * @param annotated the type, with its annotations
	 * @param raw its class
	 * @return the type's shape
	 */
	private static Shape structured(AnnotatedType annotated, Class<?> raw) {
		if (raw == List.class) {
			return ListShape.of(Shapes.of(typeArgument(annotated, 0)), annotated);
		}
		if (raw == Map.class) {
			return map(annotated);
		}
		if (raw == JsonNode.class) {
			return new AnyShape();
		}
		if (raw == ObjectNode.class) {
			return new ObjectAnyShape();
		}
		return Shapes.of(raw);
	}

	private static Shape map(AnnotatedType annotated) {
		if (raw(typeArgument(annotated, 0)) != String.class) {
			throw new IllegalArgumentException("a map's keys are strings: " + annotated);
		}
		return new MapShape(Shapes.of(typeArgument(annotated, 1)));
	}

	private static AnnotatedType typeArgument(AnnotatedType annotated, int index) {
		if (annotated instanceof AnnotatedParameterizedType parameterized) {
			return parameterized.getAnnotatedActualTypeArguments()[index];
		}
		throw new IllegalArgumentException("a raw collection type: " + annotated);
	}

}
