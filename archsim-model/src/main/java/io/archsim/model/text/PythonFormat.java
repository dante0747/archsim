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

	private PythonFormat() {
	}

	/** {@code format(x, spec)} for a Python float. */
	public static String format(double x, String spec) {
		Spec s = Spec.parse(spec);
		String body;
		if (Double.isNaN(x) || Double.isInfinite(x)) {
			body = Double.isNaN(x) ? "nan" : "inf";
			body = s.type == '%' ? body + "%" : body;
		}
		else {
			body = switch (s.type) {
				case 'f' -> fixed(Math.abs(x), s.precisionOr(6));
				case '%' -> fixed(Math.abs(x * 100), s.precisionOr(6)) + "%";
				case 'e' -> scientific(Math.abs(x), s.precisionOr(6));
				case 'g' -> general(Math.abs(x), s.precisionOr(6));
				case 'd' -> throw new IllegalArgumentException("format code 'd' is for integers: " + spec);
				default -> {
					if (s.precision != null) {
						throw new IllegalArgumentException("unsupported float format: " + spec);
					}
					yield PythonText.repr(Math.abs(x));
				}
			};
			body = group(body, s.grouping);
		}
		boolean negative = x < 0 || (x == 0 && Double.doubleToRawLongBits(x) < 0);
		return pad(sign(negative && !Double.isNaN(x), s.sign) + body, s);
	}

	/** {@code format(n, spec)} for a Python int; float types format it as a float. */
	public static String format(long n, String spec) {
		Spec s = Spec.parse(spec);
		if (s.type != 'd' && s.type != 0) {
			return format((double) n, spec);
		}
		if (s.precision != null) {
			throw new IllegalArgumentException("precision not allowed in integer format: " + spec);
		}
		String digits = Long.toString(n).replace("-", "");
		return pad(sign(n < 0, s.sign) + group(digits, s.grouping), s);
	}

	private static String fixed(double x, int precision) {
		return new BigDecimal(x).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
	}

	private static String scientific(double x, int precision) {
		if (x == 0) {
			return (precision > 0 ? "0." + "0".repeat(precision) : "0") + "e+00";
		}
		BigDecimal rounded = new BigDecimal(x).round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
		String digits = rounded.unscaledValue().toString();
		digits = digits.length() < precision + 1 ? digits + "0".repeat(precision + 1 - digits.length())
				: digits.substring(0, precision + 1);
		int exponent = rounded.precision() - rounded.scale() - 1;
		String mantissa = precision > 0 ? digits.charAt(0) + "." + digits.substring(1) : digits;
		return mantissa + PythonText.exponent(exponent);
	}

	/** {@code g}: fixed or scientific by the exponent, without trailing zeros. */
	private static String general(double x, int precision) {
		int p = Math.max(precision, 1);
		if (x == 0) {
			return "0";
		}
		BigDecimal rounded = new BigDecimal(x).round(new MathContext(p, RoundingMode.HALF_EVEN));
		int exponent = rounded.precision() - rounded.scale() - 1;
		String text = exponent >= -4 && exponent < p
				? rounded.setScale(Math.max(p - 1 - exponent, 0), RoundingMode.HALF_EVEN).toPlainString()
				: scientific(x, p - 1);
		return withoutTrailingZeros(text);
	}

	private static String withoutTrailingZeros(String text) {
		int e = text.indexOf('e');
		String mantissa = e < 0 ? text : text.substring(0, e);
		String rest = e < 0 ? "" : text.substring(e);
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
			if (i > 0 && (end - i) % 3 == 0) {
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
		return sign == '+' || sign == ' ' ? String.valueOf(sign) : "";
	}

	private static String pad(String text, Spec s) {
		int missing = s.width - text.length();
		if (missing <= 0) {
			return text;
		}
		char align = s.align != 0 ? s.align : s.zero ? '=' : '>';
		char fill = s.fill != 0 ? s.fill : s.zero ? '0' : ' ';
		String padding = String.valueOf(fill).repeat(missing);
		return switch (align) {
			case '<' -> text + padding;
			case '^' -> padding.substring(0, missing / 2) + text + padding.substring(missing / 2);
			case '=' -> {
				int signs = !text.isEmpty() && "+- ".indexOf(text.charAt(0)) >= 0 ? 1 : 0;
				yield text.substring(0, signs) + padding + text.substring(signs);
			}
			default -> padding + text;
		};
	}

	/** A parsed format spec; {@code 0} stands for a part that is not there. */
	private record Spec(char fill, char align, char sign, boolean zero, int width, char grouping,
			@Nullable Integer precision, char type) {

		static Spec parse(String spec) {
			Matcher m = SPEC.matcher(spec);
			if (!m.matches()) {
				throw new IllegalArgumentException("unsupported format spec: " + spec);
			}
			return new Spec(first(m.group("fill")), first(m.group("align")), first(m.group("sign")),
					m.group("zero") != null, m.group("width") == null ? 0 : Integer.parseInt(m.group("width")),
					first(m.group("grouping")),
					m.group("precision") == null ? null : Integer.valueOf(m.group("precision")),
					first(m.group("type")));
		}

		int precisionOr(int fallback) {
			return precision == null ? fallback : precision;
		}

		private static char first(@Nullable String group) {
			return group == null ? 0 : group.charAt(0);
		}
	}

}
