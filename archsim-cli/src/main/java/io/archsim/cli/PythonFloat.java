package io.archsim.cli;

import java.util.Locale;
import java.util.regex.Pattern;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import io.archsim.engine.text.PythonStrings;
import io.archsim.model.text.PythonText;

/**
 * A number option read as argparse's {@code type=float} read it, through Python's
 * {@code float()}: spaces around it and digits of any script, as Python turns them into
 * ASCII first (only ASCII's own six spaces are stripped then); underscores between
 * digits; {@code inf}, {@code infinity} and {@code nan} in any case; and an exponent past
 * the largest double as infinity. Java's own forms, such as {@code 1d} or {@code 0x1p3},
 * are refused, as Python refused them, as a usage error.
 */
final class PythonFloat implements ITypeConverter<Double> {

	// Python's grammar, once its digits are ASCII and its underscores gone
	private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

	private static final Pattern SPECIAL = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

	/**
	 * The spaces CPython strips from a number's ends: C's {@code isspace()}, not Java's
	 * {@code strip()}.
	 */
	private static final Pattern AROUND = Pattern.compile("^[ \\t\\n\\x0B\\f\\r]+|[ \\t\\n\\x0B\\f\\r]+$");

	// an underscore Python takes: a digit on each side
	private static final Pattern UNDERSCORE = Pattern.compile("(?<=[0-9])_(?=[0-9])");

	/** What stands for a character Python's float() reads no number from. */
	private static final char NOT_ASCII = '?';

	/**
	 * The first character CPython does not keep as it is: DELETE, then all beyond ASCII.
	 */
	private static final int KEPT_BELOW = 0x7F;

	private static final int DECIMAL_RADIX = 10;

	@Override
	public Double convert(String value) {
		String stripped = AROUND.matcher(ascii(value)).replaceAll("");
		String text = UNDERSCORE.matcher(stripped).replaceAll("");
		if (SPECIAL.matcher(text).matches()) {
			return special(text.toLowerCase(Locale.ROOT));
		}
		if (DECIMAL.matcher(text).matches()) {
			return Double.parseDouble(text);
		}
		throw new TypeConversionException("invalid float value: " + PythonText.repr(value));
	}

	// _PyUnicode_TransformDecimalAndSpaceToASCII: ASCII stays as it is; beyond it, a
	// space becomes a space, a decimal digit its ASCII digit, and any other character one
	// no number holds
	private static String ascii(String value) {
		StringBuilder text = new StringBuilder(value.length());
		value.codePoints().forEach((point) -> text.append(ascii(point)));
		return text.toString();
	}

	private static char ascii(int point) {
		if (point < KEPT_BELOW) {
			return (char) point;
		}
		if (PythonStrings.isSpace(point)) {
			return ' ';
		}
		if (Character.getType(point) == Character.DECIMAL_DIGIT_NUMBER) {
			return Character.forDigit(Character.digit(point, DECIMAL_RADIX), DECIMAL_RADIX);
		}
		return NOT_ASCII;
	}

	private static double special(String text) {
		if (text.endsWith("nan")) {
			return Double.NaN;
		}
		return text.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
	}

}
