package io.archsim.model.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link Shape} of a record, an {@link AsArray} record, or a {@link Tagged} or
 * {@link TaggedByKey} sealed interface, once per record and union. {@link TypeShapes}
 * builds the shapes of their fields.
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
			return type.isAnnotationPresent(Tagged.class) ? buildUnion(type) : buildUnionByKey(type);
		}
	};

	private Shapes() {
	}

	/**
	 * The shape of a record, or of a {@link Tagged} or {@link TaggedByKey} sealed
	 * interface, used as a whole input or as a field's value.
	 * @param type the record or the sealed interface
	 * @return its shape, built the first time it is asked for
	 */
	static Shape of(Class<?> type) {
		if (type.isAnnotationPresent(Tagged.class) || type.isAnnotationPresent(TaggedByKey.class)) {
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

	/**
	 * A union told apart by key, its records in the order {@link TaggedByKey#members()}
	 * lists them (the order of a {@code permits} clause is not one Java keeps).
	 * @param type the sealed interface
	 * @return its shape
	 * @throws IllegalArgumentException when its members are not each permitted record
	 * once, or a record's key names no field
	 */
	private static Shape buildUnionByKey(Class<?> type) {
		TaggedByKey union = type.getAnnotation(TaggedByKey.class);
		Class<?>[] permitted = type.getPermittedSubclasses();
		if (union == null || permitted == null) {
			throw new IllegalArgumentException(type + " is not a sealed interface tagged by key");
		}
		List<Class<?>> order = List.of(union.members());
		if (order.size() != permitted.length || !Set.copyOf(order).equals(Set.of(permitted))) {
			throw new IllegalArgumentException(type + " must list each of its permitted records once as members");
		}
		List<KeyUnionShape.Member> members = new ArrayList<>();
		for (Class<?> member : order) {
			members.add(memberByKey(type, member));
		}
		return new KeyUnionShape(union, members);
	}

	/**
	 * A record of a union told apart by key, picked by its tag field's JSON or Python
	 * name.
	 * @param union the sealed interface
	 * @param member the record
	 * @return the record as a member, with its keys
	 * @throws IllegalArgumentException when it has no {@link KeyTag}, or the tag names no
	 * field
	 */
	private static KeyUnionShape.Member memberByKey(Class<?> union, Class<?> member) {
		KeyTag tag = member.getAnnotation(KeyTag.class);
		if (tag == null) {
			throw new IllegalArgumentException(member + " of " + union + " has no @KeyTag");
		}
		RecordShape shape = record(member);
		Field field = shape.fields()
			.stream()
			.filter((candidate) -> candidate.name().equals(tag.value()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(member + " has no field " + tag.value()));
		return new KeyUnionShape.Member(member, tag.value(),
				List.copyOf(new LinkedHashSet<>(List.of(field.name(), field.pythonName()))), shape);
	}

}
