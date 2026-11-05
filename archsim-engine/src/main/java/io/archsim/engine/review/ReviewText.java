package io.archsim.engine.review;

import java.util.OptionalDouble;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;

/**
 * How the review writes its numbers: milliseconds, percentages, and a setting that may be
 * missing.
 */
final class ReviewText {

	/** What a setting that is missing reads as. */
	private static final String UNKNOWN = "?";

	private static final double PERCENT = 100;

	private ReviewText() {
	}

	/**
	 * Whole milliseconds with thousands separators: {@code 1,500 ms}.
	 * @param milliseconds a finite time
	 * @return its text
	 */
	static String milliseconds(double milliseconds) {
		return HalfUp.grouped(milliseconds) + " ms";
	}

	/**
	 * A fraction as a whole percentage, halves rounded up: {@code 85%}.
	 * @param fraction a finite fraction
	 * @return its text
	 */
	static String percent(double fraction) {
		return NumberText.plain(HalfUp.round(fraction * PERCENT, 0)) + "%";
	}

	/**
	 * A setting's number, or a question mark when it is missing: {@code 12},
	 * {@code 12.5}.
	 * @param value the setting's number
	 * @return its text
	 */
	static String plain(OptionalDouble value) {
		return value.isPresent() ? NumberText.plain(value.getAsDouble()) : UNKNOWN;
	}

}
