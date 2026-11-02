package io.archsim.model.codec;

import io.archsim.model.text.PythonText;
import java.math.BigDecimal;
import java.util.List;

/**
 * pydantic's error messages, word for word: users see them, in the editor and from the
 * API.
 */
final class Messages {

	static final String MISSING = "Field required";
	static final String EXTRA_FORBIDDEN = "Extra inputs are not permitted";
	static final String STRING_TYPE = "Input should be a valid string";
	static final String FLOAT_TYPE = "Input should be a valid number";
	static final String FLOAT_PARSING = "Input should be a valid number, unable to parse string as a number";
	static final String INT_TYPE = "Input should be a valid integer";
	static final String INT_PARSING = "Input should be a valid integer, unable to parse string as an integer";
	static final String INT_FROM_FLOAT = "Input should be a valid integer, got a number with a fractional part";
	static final String INT_PARSING_SIZE = "Unable to parse input string as an integer, exceeded maximum size";
	static final String FINITE_NUMBER = "Input should be a finite number";
	static final String BOOL_TYPE = "Input should be a valid boolean";
	static final String BOOL_PARSING = "Input should be a valid boolean, unable to interpret input";
	static final String LIST_TYPE = "Input should be a valid list";
	static final String TUPLE_TYPE = "Input should be a valid tuple";
	static final String DICT_TYPE = "Input should be a valid dictionary";
	static final String MODEL_ATTRIBUTES_TYPE = "Input should be a valid dictionary or object to extract fields from";

	private Messages() {
	}

	static String modelType(String modelName) {
		return "Input should be a valid dictionary or instance of " + modelName;
	}

	static String valueError(String message) {
		return "Value error, " + message;
	}

	/**
	 * {@code Input should be 'a', 'b' or 'c'}, the expected values as Python writes them.
	 */
	static String literal(List<String> expected) {
		String joined = expected.size() == 1 ? expected.getFirst()
				: String.join(", ", expected.subList(0, expected.size() - 1)) + " or " + expected.getLast();
		return "Input should be " + joined;
	}

	static List<String> quoted(List<String> values) {
		return values.stream().map(PythonText::repr).toList();
	}

	static String unionTagNotFound(String property) {
		return "Unable to extract tag using discriminator '%s'".formatted(property);
	}

	static String unionTagInvalid(String tag, String property, List<String> expected) {
		return "Input tag '%s' found using '%s' does not match any of the expected tags: %s".formatted(tag, property,
				String.join(", ", quoted(expected)));
	}

	static String stringTooShort(int min) {
		return "String should have at least %d character%s".formatted(min, plural(min));
	}

	static String stringTooLong(int max) {
		return "String should have at most %d character%s".formatted(max, plural(max));
	}

	static String patternMismatch(String pattern) {
		return "String should match pattern '%s'".formatted(pattern);
	}

	static String tooShort(String kind, int min, int actual) {
		return "%s should have at least %d item%s after validation, not %d".formatted(kind, min, plural(min), actual);
	}

	static String tooLong(String kind, int max, int actual) {
		return "%s should have at most %d item%s after validation, not %d".formatted(kind, max, plural(max), actual);
	}

	/**
	 * A bound as pydantic prints it: an integer field's as an integer, a float field's as
	 * Rust prints a float, with no exponent and no {@code .0} ({@code 600000},
	 * {@code 0.1}).
	 */
	static String bound(double value, boolean integer) {
		if (integer) {
			return Long.toString((long) value);
		}
		return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
	}

	private static String plural(int n) {
		return n == 1 ? "" : "s";
	}

}
