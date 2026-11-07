package io.archsim.engine.yaml;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.model.text.PythonText;

/**
 * Scalars read as PyYAML's safe constructor reads them, whether their tag is written or
 * resolved: {@code construct_yaml_int}, {@code construct_yaml_float} and
 * {@code construct_yaml_bool}, failing as they fail: a key looked up in vain as a
 * {@link YamlKeyException} and an index past the end as a {@link YamlScalarException}, in
 * Python's words; a number Python cannot read as a {@link YamlValueException} in its
 * words; and what PyYAML's constructor refuses as an {@link InvalidYamlException}.
 */
final class PyYamlScalars {

	private static final Map<String, Boolean> BOOLEANS = Map.of("yes", true, "no", false, "true", true, "false", false,
			"on", true, "off", false);

	/** The digits of a number in base 60 (and of a time), from its last. */
	private static final int SEXAGESIMAL = 60;

	private static final int BINARY = 2;

	private static final int OCTAL = 8;

	private static final int DECIMAL = 10;

	private static final int HEXADECIMAL = 16;

	private PyYamlScalars() {
	}

	/**
	 * A whole number: in binary ({@code 0b}), hexadecimal ({@code 0x}), octal (a leading
	 * zero), base 60 ({@code 1:30}) or decimal, underscores left out.
	 * @param scalar the scalar
	 * @return the number
	 */
	static BigInteger integer(String scalar) {
		String value = scalar.replace("_", "");
		int sign = signOf(value);
		value = unsigned(value);
		BigInteger magnitude;
		if ("0".equals(value)) {
			magnitude = BigInteger.ZERO;
		}
		else if (value.startsWith("0b")) {
			magnitude = whole(value.substring(2), BINARY);
		}
		else if (value.startsWith("0x")) {
			magnitude = whole(value.substring(2), HEXADECIMAL);
		}
		else if (first(value) == '0') {
			magnitude = whole(value, OCTAL);
		}
		else if (value.contains(":")) {
			magnitude = sexagesimal(value);
		}
		else {
			magnitude = whole(value, DECIMAL);
		}
		return (sign < 0) ? magnitude.negate() : magnitude;
	}

	/**
	 * A float: {@code .inf}, {@code .nan}, base 60 ({@code 1:30.5}) or decimal, in any
	 * case, underscores left out.
	 * @param scalar the scalar
	 * @return the number
	 */
	static double decimal(String scalar) {
		String value = scalar.replace("_", "").toLowerCase(Locale.ROOT);
		int sign = signOf(value);
		value = unsigned(value);
		if (".inf".equals(value)) {
			return sign * Double.POSITIVE_INFINITY;
		}
		if (".nan".equals(value)) {
			return Double.NaN;
		}
		if (!value.contains(":")) {
			return sign * fraction(value);
		}
		double number = 0;
		double base = 1;
		String[] digits = value.split(":", -1);
		double[] read = new double[digits.length];
		for (int i = 0; i < digits.length; i++) {
			read[i] = fraction(digits[i]);
		}
		for (int i = read.length - 1; i >= 0; i--) {
			number += read[i] * base;
			base *= SEXAGESIMAL;
		}
		return sign * number;
	}

	/**
	 * A boolean: {@code yes}, {@code no}, {@code true}, {@code false}, {@code on} or
	 * {@code off}, in any case.
	 * @param scalar the scalar
	 * @return the boolean
	 * @throws YamlKeyException in Python's words for any other word
	 */
	static boolean bool(String scalar) {
		String word = scalar.toLowerCase(Locale.ROOT);
		Boolean value = BOOLEANS.get(word);
		if (value == null) {
			throw new YamlKeyException(PythonText.repr(word));
		}
		return value;
	}

	/**
	 * Nothing, whatever the scalar says.
	 * @param scalar the scalar
	 * @return {@code null}
	 */
	static @Nullable Object none(String scalar) {
		return null;
	}

	/**
	 * Text, as written.
	 * @param scalar the scalar
	 * @return the scalar
	 */
	static String text(String scalar) {
		return scalar;
	}

	/**
	 * Binary data, kept as its base64 text once it decodes.
	 * @param scalar the scalar
	 * @return the scalar
	 * @throws InvalidYamlException when it is not base64
	 */
	static String base64(String scalar) {
		if (!StandardCharsets.US_ASCII.newEncoder().canEncode(scalar)) {
			throw notValid("failed to convert base64 data into ascii");
		}
		try {
			Base64.getMimeDecoder().decode(scalar);
		}
		catch (IllegalArgumentException ex) {
			throw notValid("failed to decode base64 data: " + ex.getMessage());
		}
		return scalar;
	}

	/**
	 * What PyYAML's constructor refuses as not valid YAML.
	 * @param problem what it refuses, in its words
	 * @return the exception
	 */
	static InvalidYamlException notValid(String problem) {
		return new InvalidYamlException(problem);
	}

	private static BigInteger sexagesimal(String value) {
		String[] digits = value.split(":", -1);
		BigInteger[] read = new BigInteger[digits.length];
		for (int i = 0; i < digits.length; i++) {
			read[i] = whole(digits[i], DECIMAL);
		}
		BigInteger number = BigInteger.ZERO;
		BigInteger base = BigInteger.ONE;
		for (int i = read.length - 1; i >= 0; i--) {
			number = number.add(read[i].multiply(base));
			base = base.multiply(BigInteger.valueOf(SEXAGESIMAL));
		}
		return number;
	}

	private static BigInteger whole(String digits, int radix) {
		try {
			return PythonNumbers.parseInt(digits, radix);
		}
		catch (NumberFormatException ex) {
			throw new YamlValueException(String.valueOf(ex.getMessage()), ex);
		}
	}

	private static double fraction(String digits) {
		try {
			return PythonNumbers.parseFloat(digits);
		}
		catch (NumberFormatException ex) {
			throw new YamlValueException(String.valueOf(ex.getMessage()), ex);
		}
	}

	/**
	 * The sign a value starts with.
	 * @param value the value
	 * @return {@code -1} when it starts with a minus, else {@code 1}
	 */
	private static int signOf(String value) {
		return (first(value) == '-') ? -1 : 1;
	}

	/**
	 * A value without the sign it starts with.
	 * @param value the value
	 * @return the value, its sign left out
	 */
	private static String unsigned(String value) {
		char first = first(value);
		return (first == '+' || first == '-') ? value.substring(1) : value;
	}

	/**
	 * {@code value[0]}.
	 * @param value the value
	 * @return its first character
	 * @throws YamlScalarException in Python's words for an empty value
	 */
	private static char first(String value) {
		if (value.isEmpty()) {
			throw new YamlScalarException("string index out of range");
		}
		return value.charAt(0);
	}

	/**
	 * How a scalar is read.
	 */
	@FunctionalInterface
	interface Reading {

		/**
		 * Reads a scalar.
		 * @param scalar the scalar, as written
		 * @return its value
		 */
		@Nullable Object read(String scalar);

	}

}
