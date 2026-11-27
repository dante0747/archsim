package io.archsim.model.codec;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code float}, a Java {@code double}. Lax as pydantic is: an integer, a
 * boolean or a numeric string ({@code "1.5"}) is a number too.
 *
 * <p>
 * Stricter than pydantic's lax mode in one thing: a float that is not finite is refused,
 * however the input writes it ({@code "nan"}, {@code "inf"}, {@code 1e999}). No setting
 * of a design means NaN or an infinity, and a comparison with NaN is false whichever way
 * it asks, so a bound or a limit checked later would let one through.
 *
 * @param bounds the bounds the number must keep to
 */
record FloatShape(Bounds bounds) implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		Optional<Double> number = number(in, at, errors);
		if (number.isEmpty()) {
			return null;
		}
		if (!Double.isFinite(number.get())) {
			errors.add("finite_number", at, Messages.FINITE_NUMBER);
			return null;
		}
		this.bounds.check(number.get(), at, errors);
		return number.get();
	}

	private static Optional<Double> number(JsonNode in, Location at, Errors errors) {
		if (in.isNumber()) {
			double value = in.isIntegralNumber() ? in.bigIntegerValue().doubleValue() : in.doubleValue();
			if (in.isIntegralNumber() && Double.isInfinite(value)) {
				// Python: int too large to convert to float
				errors.add("float_type", at, Messages.FLOAT_TYPE);
				return Optional.empty();
			}
			return Optional.of(value);
		}
		if (in.isBoolean()) {
			return Optional.of(in.booleanValue() ? 1.0 : 0.0);
		}
		if (in.isString()) {
			return fromText(in.stringValue(), at, errors);
		}
		errors.add("float_type", at, Messages.FLOAT_TYPE);
		return Optional.empty();
	}

	/**
	 * The number a text stands for, when pydantic can encode it to UTF-8 and parse one
	 * from it.
	 */
	private static Optional<Double> fromText(String text, Location at, Errors errors) {
		if (Utf8Encoding.cannotEncode(text)) {
			Utf8Encoding.refuse(at, errors);
			return Optional.empty();
		}
		Optional<Double> parsed = LaxNumbers.parseFloat(text);
		if (parsed.isEmpty()) {
			errors.add("float_parsing", at, Messages.FLOAT_PARSING);
		}
		return parsed;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return NODES.numberNode((Double) Objects.requireNonNull(value));
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode().put("type", "number");
		this.bounds.describe(schema);
		return schema;
	}
}
