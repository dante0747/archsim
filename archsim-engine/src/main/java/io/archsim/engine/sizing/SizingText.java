package io.archsim.engine.sizing;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;

/** The words auto-sizing explains its sizes with. */
final class SizingText {

	private static final double MIB_PER_GIB = 1024;

	private static final double PERCENT = 100;

	private SizingText() {
	}

	/**
	 * A short figure: {@code 1,234}, {@code 12.3}, {@code 0.42}.
	 * @param value the figure
	 * @return its text
	 */
	static String brief(double value) {
		return NumberText.brief(value);
	}

	/**
	 * A float without a point when it is whole: {@code 4}, {@code 2.5}.
	 * @param value the float
	 * @return its text
	 */
	static String plain(double value) {
		return NumberText.plain(value);
	}

	/**
	 * An amount of memory: in MiB under 1 GiB ({@code 364 MiB}), else in GiB.
	 * @param gib the memory, in GiB
	 * @return its text
	 */
	static String gib(double gib) {
		if (gib < 1) {
			return (long) HalfUp.round(gib * MIB_PER_GIB, 0) + " MiB";
		}
		return brief(gib) + " GiB";
	}

	/**
	 * A fraction as a whole percentage: {@code 60%}.
	 * @param fraction the fraction
	 * @return its text
	 */
	static String percent(double fraction) {
		return (long) HalfUp.round(fraction * PERCENT, 0) + "%";
	}

	/**
	 * The headroom auto memory has.
	 * @return {@code +25% headroom}
	 */
	static String headroom() {
		return "+" + percent(Sizing.MEMORY_HEADROOM - 1) + " headroom";
	}

	/**
	 * How full auto storage gets.
	 * @return {@code kept under 70% full}
	 */
	static String fill() {
		return "kept under " + percent(Sizing.STORAGE_FILL) + " full";
	}

	/**
	 * The ending of a plural.
	 * @param count how many
	 * @return {@code ""} for one, {@code "s"} for any other count
	 */
	static String plural(long count) {
		return (count == 1) ? "" : "s";
	}

}
