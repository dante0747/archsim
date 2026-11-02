package io.archsim.model.codec;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the {@link Shape} of a Java type from its annotations, once per record and
 * union.
 *
 * <p>
 * The types a record may use: {@link String}, {@code double}, {@code int}, {@code long},
 * {@code boolean} (or their boxes), a {@link JsonEnum}, a {@link List} or a {@link Map}
 * with string keys of these, {@link JsonNode} (any JSON), {@link ObjectNode} (any JSON
 * object), another record, or a {@link Tagged} sealed interface; {@code @Nullable} on any
 * of them allows {@code null}.
 */
final class Shapes {

	private static final ClassValue<RecordShape> RECORDS = new ClassValue<>() {
		@Override
		protected RecordShape computeValue(Class<?> type) {
			return new RecordShape(type, null);
		}
	};

	private static final ClassValue<Shape> UNIONS = new ClassValue<>() {
		@Override
		protected Shape computeValue(Class<?> type) {
			return buildUnion(type);
		}
	};

	private Shapes() {
	}

	/**
	 * The shape of a record or a {@link Tagged} sealed interface, used as a whole input.
	 * @param type the record or the sealed interface
	 * @return its shape, built the first time it is asked for
	 */
	static Shape of(Class<?> type) {
		if (type.isAnnotationPresent(Tagged.class)) {
			return UNIONS.get(type);
		}
		if (type.isRecord()) {
			return type.isAnnotationPresent(AsArray.class) ? tuple(type) : RECORDS.get(type);
		}
		throw new IllegalArgumentException(type + " is neither a record nor a tagged union");
	}

	/**
	 * The shape of a field or list item, from its annotated type.
	 * @param annotated the type, with its annotations
	 * @return its shape, which reads {@code null} too when the type is {@code @Nullable}
	 */
	static Shape of(AnnotatedType annotated) {
		if (annotated.isAnnotationPresent(Nullable.class)) {
			return new NullableShape(TypeShapes.of(annotated));
		}
		return TypeShapes.of(annotated);
	}

	static RecordShape record(Class<?> type) {
		return RECORDS.get(type);
	}

	private static Shape buildUnion(Class<?> type) {
		Tagged tagged = type.getAnnotation(Tagged.class);
		Class<?>[] permitted = type.getPermittedSubclasses();
		if (tagged == null || permitted == null) {
			throw new IllegalArgumentException(type + " is not a tagged sealed interface");
		}
		Map<String, RecordShape> members = new LinkedHashMap<>();
		Map<Class<?>, String> tags = new LinkedHashMap<>();
		for (Class<?> member : permitted) {
			Tag tag = member.getAnnotation(Tag.class);
			if (tag == null) {
				throw new IllegalArgumentException(member + " of " + type + " has no @Tag");
			}
			members.put(tag.value(), new RecordShape(member, new RecordShape.TagField(tagged.value(), tag.value())));
			tags.put(member, tag.value());
		}
		return new UnionShape(tagged.value(), members, tags);
	}

	private static Shape tuple(Class<?> type) {
		RecordComponent[] components = type.getRecordComponents();
		List<Shape> items = Arrays.stream(components).map((component) -> of(component.getAnnotatedType())).toList();
		return new TupleShape(items,
				Arrays.stream(components).map((component) -> Reflection.accessible(component.getAccessor())).toList(),
				Reflection.canonicalConstructor(type, components));
	}

}
