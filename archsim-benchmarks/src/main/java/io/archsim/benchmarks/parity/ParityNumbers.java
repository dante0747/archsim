package io.archsim.benchmarks.parity;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * Numbers as the parity page writes them: medians to four decimals without trailing
 * zeros, shares as percentages to two decimals, seconds to two, and None in words.
 */
final class ParityNumbers {

	static final String NOT_MEASURED = "not measured";

	private static final double HUNDRED = 100;

	private ParityNumbers() {
	}

	/**
	 * A median or a bound: {@code 1,234.5}, {@code 20}.
	 * @param value the number, or None
	 * @return its text, or {@code none}
	 */
	static String number(JsonNode value) {
		if (PythonValues.isNone(value)) {
			return "none";
		}
		String text = PythonFormat.format(value.doubleValue(), ",.4f");
		text = text.replaceAll("0+$", "");
		return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
	}

	/**
	 * A share as a percentage: {@code 5.00%}.
	 * @param share the share, or None
	 * @return its text, or {@code not measured}
	 */
	static String percent(JsonNode share) {
		return PythonValues.isNone(share) ? NOT_MEASURED
				: PythonFormat.format(share.doubleValue() * HUNDRED, ".2f") + "%";
	}

	/**
	 * Seconds: {@code 0.15 s}.
	 * @param seconds the seconds, or None
	 * @return their text, or {@code not measured}
	 */
	static String seconds(JsonNode seconds) {
		return PythonValues.isNone(seconds) ? NOT_MEASURED : PythonFormat.format(seconds.doubleValue(), ".2f") + " s";
	}

	/**
	 * A speed-up: {@code x10.0}.
	 * @param times the times faster, or None
	 * @return its text, or {@code not measured}
	 */
	static String times(JsonNode times) {
		return PythonValues.isNone(times) ? NOT_MEASURED : "x" + PythonText.str(times);
	}

	/**
	 * A count with thousands separators: {@code 2,980}.
	 * @param count the count
	 * @return its text
	 */
	static String count(long count) {
		return PythonFormat.format(count, ",");
	}

	/**
	 * {@code yes} or {@code no}.
	 * @param value whether
	 * @return the word
	 */
	static String yes(boolean value) {
		return value ? "yes" : "no";
	}

}
