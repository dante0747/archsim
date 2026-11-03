package io.archsim.model.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@link Shape} of a record, an {@link AsArray} record or a {@link Tagged}
 * sealed interface, once per record and union. {@link TypeShapes} builds the shapes of
 * their fields.
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
	 * The shape of a record or a {@link Tagged} sealed interface, used as a whole input
	 * or as a field's value.
	 * @param type the record or the sealed interface
	 * @return its shape, built the first time it is asked for
	 */
	static Shape of(Class<?> type) {
		if (type.isAnnotationPresent(Tagged.class)) {
			return UNIONS.get(type);
		}
		if (type.isRecord()) {
			return type.isAnnotationPresent(AsArray.class) ? TupleShape.of(type) : RECORDS.get(type);
		}
		throw new IllegalArgumentException(type + " is neither a record nor a tagged union");
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

}
