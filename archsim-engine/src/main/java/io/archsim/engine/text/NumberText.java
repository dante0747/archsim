package io.archsim.engine.text;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The ways the engine writes a number in its texts: formulas, percentages, short figures
 * and latencies. The web app shows these texts as they are, so they are written exactly
 * as the Python engine wrote them.
 */
public final class NumberText {

	/** What a number that is missing, or not finite, reads as: an en dash. */
	public static final String DASH = "–";

	/** From this magnitude on, a figure has no decimals. */
	private static final double NO_DECIMALS_FROM = 100;

	/** From this magnitude on, a figure has one decimal fewer than below it. */
	private static final double FEWER_DECIMALS_FROM = 10;

	/** From this many milliseconds on, a latency is whole milliseconds. */
	private static final double WHOLE_MILLISECONDS_FROM = 10;

	/** A fraction times this is a percentage. */
	private static final double PERCENT = 100;

	/** Tenths in one. */
	private static final double TENTHS = 10;

	private static final Pattern TRAILING_ZEROS = Pattern.compile("0+$");

	private static final Pattern TRAILING_POINT = Pattern.compile("\\.$");

	private NumberText() {
	}

	/**
	 * A float without a point when it is whole, else as Python's {@code repr}:
	 * {@code 12}, {@code 12.5}, {@code 1e-05}.
	 * @param value the float
	 * @return its text
	 */
	public static String plain(double value) {
		if (Double.isFinite(value) && value == Math.rint(value)) {
			return new BigDecimal(value).toBigInteger().toString();
		}
		return PythonText.repr(value);
	}

	/**
	 * A number in a formula: thousands separators, no decimals from 100 on, and otherwise
	 * {@code digits} decimals (one more under 10) without trailing zeros: {@code 1,234},
	 * {@code 12.3}, {@code 2.67}.
	 * @param value the number, or {@code null} when there is none
	 * @param digits the decimals from 10 to 100
	 * @return its text, or {@link #DASH} when there is no finite value
	 */
	public static String formula(@Nullable Double value, int digits) {
		if (value == null || !Double.isFinite(value)) {
			return DASH;
		}
		if (Math.abs(value) >= NO_DECIMALS_FROM) {
			return PythonFormat.format(value, ",.0f");
		}
		int decimals = (Math.abs(value) >= FEWER_DECIMALS_FROM) ? digits : digits + 1;
		String text = PythonFormat.format(value, ",." + decimals + "f");
		return text.contains(".") ? withoutTrailingZeros(text) : text;
	}

	/**
	 * A fraction as a percentage with one decimal, none when it is zero: {@code 1.2%},
	 * {@code 50%}.
	 * @param fraction the fraction, or {@code null} when there is none
	 * @return its text, or {@link #DASH} when there is none
	 */
	public static String percent(@Nullable Double fraction) {
		if (fraction == null) {
			return DASH;
		}
		return (PythonFormat.format(fraction * PERCENT, ".1f") + "%").replace(".0%", "%");
	}

	/**
	 * A short figure for an explanation: a whole number with separators from 100 on, one
	 * decimal from 10 on, and two below, halves rounded up: {@code 1,235}, {@code 12.3},
	 * {@code 0.13}.
	 * @param value the figure
	 * @return its text, or {@link #DASH} when it is not finite
	 */
	public static String brief(double value) {
		if (!Double.isFinite(value)) {
			return DASH;
		}
		if (Math.abs(value) >= NO_DECIMALS_FROM) {
			return HalfUp.grouped(value);
		}
		return plain(HalfUp.round(value, (Math.abs(value) >= FEWER_DECIMALS_FROM) ? 1 : 2));
	}

	/**
	 * A latency as the web app writes it: one decimal under 10 ms, else whole
	 * milliseconds, halves rounded up as JavaScript rounds them: {@code 9.9},
	 * {@code 10.0}, {@code 12}.
	 * @param value the latency, in milliseconds
	 * @return its text, without the unit
	 */
	public static String milliseconds(double value) {
		if (value < WHOLE_MILLISECONDS_FROM) {
			return PythonFormat.format(HalfUp.javaScriptRound(value * TENTHS) / TENTHS, ".1f");
		}
		return PythonFormat.format(HalfUp.javaScriptRound(value), ".0f");
	}

	/**
	 * A number's text without the zeros after its point, nor the point when nothing is
	 * left after it: Python's {@code text.rstrip("0").rstrip(".")}.
	 * @param text the number's text, with a point
	 * @return the text, trimmed
	 */
	private static String withoutTrailingZeros(String text) {
		String trimmed = TRAILING_ZEROS.matcher(text).replaceFirst("");
		return TRAILING_POINT.matcher(trimmed).replaceFirst("");
	}

}
