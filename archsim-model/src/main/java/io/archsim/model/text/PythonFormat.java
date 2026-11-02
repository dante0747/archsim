package io.archsim.model.text;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Python's format-spec mini-language for numbers, as f-strings use it:
 * {@code format(0.25, "g")} is {@code 0.25}, {@code format(0.8, ".0%")} is {@code 80%},
 * {@code format(12345.6, ",.0f")} is {@code 12,346}. ArchSim's messages were written with
 * these specs, and their numbers must read the same in Java (ADR-0027).
 *
 * <p>
 * Supported: {@code [[fill]align][sign][0][width][,|_][.precision][type]} with the types
 * {@code d}, {@code f}, {@code e}, {@code g}, {@code %} and none. Rounding is Python's:
 * the exact binary value, rounded half to even. Anything else throws, so a spec is never
 * silently formatted differently.
 */
public final class PythonFormat {

	private static final Pattern SPEC = Pattern
		.compile("(?:(?<fill>.)?(?<align>[<>=^]))?(?<sign>[-+ ])?(?<zero>0)?(?<width>\\d+)?"
				+ "(?<grouping>[,_])?(?:\\.(?<precision>\\d+))?(?<type>[defg%])?");

	/**
	 * The precision of {@code f}, {@code e}, {@code g} and {@code %} when a spec gives
	 * none.
	 */
	private static final int DEFAULT_PRECISION = 6;

	/** A {@code %} spec writes the number times this. */
	private static final int PERCENT = 100;

	/**
	 * The lowest exponent {@code g} writes in fixed notation ({@code 0.0001}, but
	 * {@code 1e-05}).
	 */
	private static final int GENERAL_LOWEST_FIXED_EXPONENT = -4;

	/** The digits between two grouping separators. */
	private static final int GROUP_SIZE = 3;

	private PythonFormat() {
	}

	/**
	 * {@code format(value, spec)} for a Python float.
	 * @param value the float
	 * @param spec the format spec, such as {@code ".0%"}
	 * @return the float formatted as Python formats it
	 * @throws IllegalArgumentException when the spec is not supported
	 */
	public static String format(double value, String spec) {
		Spec parsed = Spec.parse(spec);
		String body = Double.isFinite(value) ? finite(value, parsed) : nonFinite(value, parsed);
		return pad(sign(isNegative(value), parsed.sign) + body, parsed);
	}

	/**
	 * {@code format(value, spec)} for a Python int; float types format it as a float.
	 * @param value the integer
	 * @param spec the format spec, such as {@code ",d"}
	 * @return the integer formatted as Python formats it
	 * @throws IllegalArgumentException when the spec is not supported
	 */
	public static String format(long value, String spec) {
		Spec parsed = Spec.parse(spec);
		if (parsed.type != 'd' && parsed.type != 0) {
			return format((double) value, spec);
		}
		if (parsed.precision != null) {
			throw new IllegalArgumentException("precision not allowed in integer format: " + spec);
		}
		String digits = Long.toString(value).replace("-", "");
		return pad(sign(value < 0, parsed.sign) + group(digits, parsed.grouping), parsed);
	}

	/**
	 * A finite float's digits, grouped, without its sign.
	 * @param value the float
	 * @param spec the parsed spec
	 * @return the digits, with the spec's type and precision
	 */
	private static String finite(double value, Spec spec) {
		String body = switch (spec.type) {
			case 'f' -> fixed(Math.abs(value), spec.precisionOr(DEFAULT_PRECISION));
			case '%' -> fixed(Math.abs(value * PERCENT), spec.precisionOr(DEFAULT_PRECISION)) + "%";
			case 'e' -> scientific(Math.abs(value), spec.precisionOr(DEFAULT_PRECISION));
			case 'g' -> general(Math.abs(value), spec.precisionOr(DEFAULT_PRECISION));
			case 'd' -> throw new IllegalArgumentException("format code 'd' is for integers: " + spec.text);
			default -> shortest(Math.abs(value), spec);
		};
		return group(body, spec.grouping);
	}

	/**
	 * NaN or an infinity by name, without its sign.
	 * @param value a NaN or an infinity
	 * @param spec the parsed spec
	 * @return {@code nan} or {@code inf}, with a {@code %} for a percentage
	 */
	private static String nonFinite(double value, Spec spec) {
		String body = Double.isNaN(value) ? "nan" : "inf";
		return (spec.type == '%') ? body + "%" : body;
	}

	/**
	 * A spec without a type: the float's repr, which takes no precision.
	 * @param magnitude the float, not below zero
	 * @param spec the parsed spec
	 * @return the float's repr
	 */
	private static String shortest(double magnitude, Spec spec) {
		if (spec.precision != null) {
			throw new IllegalArgumentException("unsupported float format: " + spec.text);
		}
		return PythonText.repr(magnitude);
	}

	/**
	 * Whether Python writes the float with a minus sign: below zero, or negative zero.
	 * @param value the float
	 * @return whether it is negative; NaN is not
	 */
	private static boolean isNegative(double value) {
		boolean negativeZero = value == 0 && Double.doubleToRawLongBits(value) < 0;
		return value < 0 || negativeZero;
	}

	private static String fixed(double magnitude, int precision) {
		return new BigDecimal(magnitude).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
	}

	private static String scientific(double magnitude, int precision) {
		if (magnitude == 0) {
			return ((precision > 0) ? "0." + "0".repeat(precision) : "0") + "e+00";
		}
		BigDecimal rounded = new BigDecimal(magnitude).round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
		String digits = rounded.unscaledValue().toString();
		digits = (digits.length() < precision + 1) ? digits + "0".repeat(precision + 1 - digits.length())
				: digits.substring(0, precision + 1);
		int exponent = rounded.precision() - rounded.scale() - 1;
		String mantissa = (precision > 0) ? digits.charAt(0) + "." + digits.substring(1) : digits;
		return mantissa + PythonText.exponent(exponent);
	}

	/**
	 * {@code g}: fixed or scientific by the exponent, without trailing zeros.
	 * @param magnitude the float, not below zero
	 * @param precision the significant digits asked for
	 * @return the float's digits
	 */
	private static String general(double magnitude, int precision) {
		int significant = Math.max(precision, 1);
		if (magnitude == 0) {
			return "0";
		}
		BigDecimal rounded = new BigDecimal(magnitude).round(new MathContext(significant, RoundingMode.HALF_EVEN));
		int exponent = rounded.precision() - rounded.scale() - 1;
		String text = (exponent >= GENERAL_LOWEST_FIXED_EXPONENT && exponent < significant)
				? rounded.setScale(Math.max(significant - 1 - exponent, 0), RoundingMode.HALF_EVEN).toPlainString()
				: scientific(magnitude, significant - 1);
		return withoutTrailingZeros(text);
	}

	private static String withoutTrailingZeros(String text) {
		int exponentStart = text.indexOf('e');
		String mantissa = (exponentStart < 0) ? text : text.substring(0, exponentStart);
		String rest = (exponentStart < 0) ? "" : text.substring(exponentStart);
		if (mantissa.contains(".")) {
			mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return mantissa + rest;
	}

	private static String group(String number, char grouping) {
		if (grouping == 0) {
			return number;
		}
		int end = 0;
		while (end < number.length() && Character.isDigit(number.charAt(end))) {
			end++;
		}
		StringBuilder grouped = new StringBuilder();
		for (int i = 0; i < end; i++) {
			if (i > 0 && (end - i) % GROUP_SIZE == 0) {
				grouped.append(grouping);
			}
			grouped.append(number.charAt(i));
		}
		return grouped + number.substring(end);
	}

	private static String sign(boolean negative, char sign) {
		if (negative) {
			return "-";
		}
		return (sign == '+' || sign == ' ') ? String.valueOf(sign) : "";
	}

	private static String pad(String text, Spec spec) {
		int missing = spec.width - text.length();
		if (missing <= 0) {
			return text;
		}
		String padding = String.valueOf(spec.fillCharacter()).repeat(missing);
		return switch (spec.alignment()) {
			case '<' -> text + padding;
			case '^' -> padding.substring(0, missing / 2) + text + padding.substring(missing / 2);
			case '=' -> padAfterSign(text, padding);
			default -> padding + text;
		};
	}

	/**
	 * The {@code =} alignment: the padding goes between the sign and the digits.
	 * @param text the number, maybe with a sign
	 * @param padding the fill to add
	 * @return the padded number
	 */
	private static String padAfterSign(String text, String padding) {
		int signs = (!text.isEmpty() && "+- ".indexOf(text.charAt(0)) >= 0) ? 1 : 0;
		return text.substring(0, signs) + padding + text.substring(signs);
	}

	/**
	 * A parsed format spec, with its text; {@code 0} stands for a part that is not there.
	 */
	private record Spec(String text, char fill, char align, char sign, boolean zero, int width, char grouping,
			@Nullable Integer precision, char type) {

		static Spec parse(String spec) {
			Matcher matcher = SPEC.matcher(spec);
			if (!matcher.matches()) {
				throw new IllegalArgumentException("unsupported format spec: " + spec);
			}
			return new Spec(spec, first(matcher.group("fill")), first(matcher.group("align")),
					first(matcher.group("sign")), matcher.group("zero") != null, widthOf(matcher.group("width")),
					first(matcher.group("grouping")), precisionOf(matcher.group("precision")),
					first(matcher.group("type")));
		}

		int precisionOr(int fallback) {
			return (this.precision != null) ? this.precision : fallback;
		}

		/**
		 * The spec's alignment; else after the sign when it pads with zeros, else right.
		 * @return {@code <}, {@code >}, {@code ^} or {@code =}
		 */
		char alignment() {
			if (this.align != 0) {
				return this.align;
			}
			return this.zero ? '=' : '>';
		}

		/**
		 * The spec's fill; else zeros when it pads with zeros, else spaces.
		 * @return the character to pad with
		 */
		char fillCharacter() {
			if (this.fill != 0) {
				return this.fill;
			}
			return this.zero ? '0' : ' ';
		}

		private static int widthOf(@Nullable String group) {
			return (group != null) ? Integer.parseInt(group) : 0;
		}

		private static @Nullable Integer precisionOf(@Nullable String group) {
			return (group != null) ? Integer.valueOf(group) : null;
		}

		private static char first(@Nullable String group) {
			return (group != null) ? group.charAt(0) : 0;
		}

	}

}
