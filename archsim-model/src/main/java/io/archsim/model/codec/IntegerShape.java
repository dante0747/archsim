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
 */
record IntegerShape(Bounds bounds, boolean isLong) implements Shape {

	private static final BigDecimal LONG_LIMIT = new BigDecimal(BigInteger.TWO.pow(63));

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		Optional<BigInteger> number = integer(in, at, errors);
		if (number.isEmpty()) {
			return null;
		}
		int mark = errors.mark();
		bounds.check(new BigDecimal(number.get()), at, errors);
		if (errors.since(mark)) {
			return null;
		}
		BigInteger value = number.get();
		if (value.bitLength() > (isLong ? 63 : 31)) {
			errors.add("int_parsing_size", at, Messages.INT_PARSING_SIZE);
			return null;
		}
		return isLong ? (Object) value.longValue() : (Object) value.intValue();
	}

	private static Optional<BigInteger> integer(JsonNode in, Location at, Errors errors) {
		if (in.isIntegralNumber()) {
			return Optional.of(in.bigIntegerValue());
		}
		if (in.isNumber()) {
			double x = in.doubleValue();
			if (!Double.isFinite(x)) {
				errors.add("finite_number", at, Messages.FINITE_NUMBER);
			}
			else if (x != Math.rint(x)) {
				errors.add("int_from_float", at, Messages.INT_FROM_FLOAT);
			}
			else if (new BigDecimal(x).abs().compareTo(LONG_LIMIT) >= 0) {
				errors.add("int_parsing_size", at, Messages.INT_PARSING_SIZE);
			}
			else {
				return Optional.of(new BigDecimal(x).toBigIntegerExact());
			}
			return Optional.empty();
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

	@Override
	public JsonNode write(@Nullable Object value) {
		long number = ((Number) Objects.requireNonNull(value)).longValue();
		// the smallest node, as Jackson reads the same number
		return number == (int) number ? NODES.numberNode((int) number) : NODES.numberNode(number);
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode().put("type", "integer");
		bounds.describe(schema);
		return schema;
	}
}
