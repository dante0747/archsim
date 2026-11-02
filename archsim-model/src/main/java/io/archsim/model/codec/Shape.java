package io.archsim.model.codec;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How one Java type maps to JSON: reading an input with pydantic's rules, writing a value
 * back, and describing it in JSON Schema. {@link Shapes} builds the shape of a type from
 * its annotations.
 */
sealed interface Shape permits AnyShape, BooleanShape, EnumShape, FloatShape, IntChoiceShape, IntegerShape, ListShape,
		MapShape, NullableShape, ObjectAnyShape, RecordShape, StringShape, TupleShape, UnionShape {

	JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * The value {@code in} holds. When it does not fit, the shape adds at least one error
	 * at {@code at} or below it, and what it returns is meaningless.
	 */
	@Nullable Object read(JsonNode in, Location at, Errors errors);

	/** The JSON of a value this shape reads. */
	JsonNode write(@Nullable Object value);

	/**
	 * This shape's JSON Schema, in pydantic's dialect; records go to {@code definitions}.
	 */
	ObjectNode schema(Definitions definitions);

}
