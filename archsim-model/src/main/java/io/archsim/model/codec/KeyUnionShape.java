package io.archsim.model.codec;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic union with a callable discriminator that names a member by a key it finds,
 * as a {@link TaggedByKey} sealed interface: the first member one of whose keys the input
 * has is read, and errors inside it are located under its tag
 * ({@code serviceTimeMs.histogram.unit}).
 */
final class KeyUnionShape implements Shape {

	private final String errorType;

	private final String errorMessage;

	private final List<Member> members;

	KeyUnionShape(TaggedByKey union, List<Member> members) {
		this.errorType = union.errorType();
		this.errorMessage = union.errorMessage();
		this.members = List.copyOf(members);
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (in.isObject()) {
			for (Member member : this.members) {
				if (member.keys().stream().anyMatch(in::has)) {
					return member.shape().read(in, at.child(member.tag()), errors);
				}
			}
		}
		errors.add(this.errorType, at, this.errorMessage);
		return null;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		Class<?> type = Objects.requireNonNull(value).getClass();
		return this.members.stream()
			.filter((member) -> member.type() == type)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(type + " is not a member of the union"))
			.shape()
			.write(value);
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		ArrayNode oneOf = schema.putArray("oneOf");
		this.members.forEach((member) -> oneOf.add(definitions.reference(member.shape())));
		return schema;
	}

	/**
	 * One record of the union.
	 *
	 * @param type the record's class
	 * @param tag where its errors are located
	 * @param keys the keys that pick it
	 * @param shape its shape
	 */
	record Member(Class<?> type, String tag, List<String> keys, RecordShape shape) {

		Member {
			keys = List.copyOf(keys);
		}

	}

}
