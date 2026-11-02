package io.archsim.model.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code int}, a Java {@code int} or {@code long}. Lax as pydantic is: a float
 * without a fraction ({@code 5.0}), a boolean or an integer string ({@code "5"}) is an
 * integer too.
 *
 * <p>
 * Python's integers have no limit; one that does not fit the Java type is reported as too
 * large. Every bounded field's bounds fit its type, so only an unbounded field can tell.
 *
 * @param bounds the bounds the integer must keep to
 * @param isLong whether the Java type is a {@code long} rather than an {@code int}
 */
record IntegerShape(Bounds bounds, boolean isLong) implements Shape {

	/** The bits of a {@code long}'s magnitude, its sign aside. */
	private static final int LONG_MAGNITUDE_BITS = Long.SIZE - 1;

	/** The bits of an {@code int}'s magnitude, its sign aside. */
	private static final int INT_MAGNITUDE_BITS = Integer.SIZE - 1;

	private static final BigDecimal LONG_LIMIT = new BigDecimal(BigInteger.TWO.pow(LONG_MAGNITUDE_BITS));

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		Optional<BigInteger> number = integer(in, at, errors);
		if (number.isEmpty()) {
			return null;
		}
		int mark = errors.mark();
		this.bounds.check(new BigDecimal(number.get()), at, errors);
		if (errors.since(mark)) {
			return null;
		}
		BigInteger value = number.get();
		if (value.bitLength() > (this.isLong ? LONG_MAGNITUDE_BITS : INT_MAGNITUDE_BITS)) {
			errors.add("int_parsing_size", at, Messages.INT_PARSING_SIZE);
			return null;
		}
		return this.isLong ? (Object) value.longValue() : (Object) value.intValue();
	}

	private static Optional<BigInteger> integer(JsonNode in, Location at, Errors errors) {
		if (in.isIntegralNumber()) {
			return Optional.of(in.bigIntegerValue());
		}
		if (in.isNumber()) {
			return fromFloat(in.doubleValue(), at, errors);
		}
		if (in.isBoolean()) {
			return Optional.of(in.booleanValue() ? BigInteger.ONE : BigInteger.ZERO);
		}
		if (in.isString()) {
			Optional<BigInteger> parsed = LaxNumbers.parseInteger(in.stringValue());
			if (parsed.isEmpty()) {
				errors.add("int_parsing", at, Messages.INT_PARSING);
			}
			return parsed;
		}
		errors.add("int_type", at, Messages.INT_TYPE);
		return Optional.empty();
	}

	/** The integer a float stands for, when it is finite, whole and within a long. */
	private static Optional<BigInteger> fromFloat(double number, Location at, Errors errors) {
		if (!Double.isFinite(number)) {
			errors.add("finite_number", at, Messages.FINITE_NUMBER);
		}
		else if (number != Math.rint(number)) {
			errors.add("int_from_float", at, Messages.INT_FROM_FLOAT);
		}
		else if (new BigDecimal(number).abs().compareTo(LONG_LIMIT) >= 0) {
			errors.add("int_parsing_size", at, Messages.INT_PARSING_SIZE);
		}
		else {
			return Optional.of(new BigDecimal(number).toBigIntegerExact());
		}
		return Optional.empty();
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		long number = ((Number) Objects.requireNonNull(value)).longValue();
		// the smallest node, as Jackson reads the same number
		return (number == (int) number) ? NODES.numberNode((int) number) : NODES.numberNode(number);
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode().put("type", "integer");
		this.bounds.describe(schema);
		return schema;
	}

}
