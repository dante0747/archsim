package io.archsim.app.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Prometheus answer's body read as Python's {@code json.loads} reads bytes: UTF-8, -16
 * or -32, any value at the top, {@code NaN} and {@code Infinity}, a key written twice
 * with its last value, and nothing after the value. What Python cannot read is not JSON,
 * nesting past Python's recursion limit included, which Python failed on with a
 * {@code RecursionError}.
 */
final class JsonAnswers {

	/** The deepest an answer may nest; Python's own limit is its recursion limit. */
	private static final int MAX_DEPTH = 1000;

	/** The most digits a number may have: Python's most for a whole number. */
	private static final int MAX_NUMBER_DIGITS = 4300;

	/** The size cap bounds strings and keys, as Python reads them of any length. */
	private static final JsonMapper JSON = JsonMapper
		.builder(JsonFactory.builder()
			.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
			.streamReadConstraints(StreamReadConstraints.builder()
				.maxNestingDepth(MAX_DEPTH)
				.maxNumberLength(MAX_NUMBER_DIGITS)
				.maxNameLength(Integer.MAX_VALUE)
				.maxStringLength(Integer.MAX_VALUE)
				.build())
			.build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private JsonAnswers() {
	}

	/**
	 * A body's value.
	 * @param body the bytes
	 * @return its value; {@code null} for what is not JSON
	 */
	static @Nullable JsonNode read(byte[] body) {
		try {
			return JSON.readTree(body);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

}
