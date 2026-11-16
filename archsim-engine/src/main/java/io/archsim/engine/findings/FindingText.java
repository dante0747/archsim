package io.archsim.engine.findings;

import org.jspecify.annotations.Nullable;

import io.archsim.model.text.PythonFormat;

/** How a run's findings write their figures. */
final class FindingText {

	/** Milliseconds in a second. */
	static final double MS_PER_SECOND = 1000;

	/** From here, a time reads in seconds. */
	private static final double SECONDS_FROM = 10_000;

	/** From here, a time reads in whole milliseconds. */
	private static final double WHOLE_MS_FROM = 10;

	/** Shares this close to 1 read with two decimals. */
	private static final double NEARLY_ALL = 0.995;

	private FindingText() {
	}

	/**
	 * A rate: {@code 1,234 req/s}.
	 * @param rps the requests per second, or {@code null}
	 * @return its text; {@code n/a} without one
	 */
	static String rps(@Nullable Double rps) {
		return (rps != null) ? PythonFormat.format(rps, ",.0f") + " req/s" : "n/a";
	}

	/**
	 * A time: {@code 12.3 s} from 10 s, {@code 1,234 ms} from 10 ms, else {@code 4.2 ms}.
	 * @param ms the time in milliseconds, or {@code null}
	 * @return its text; {@code n/a} without one
	 */
	static String ms(@Nullable Double ms) {
		if (ms == null) {
			return "n/a";
		}
		if (ms >= SECONDS_FROM) {
			return PythonFormat.format(ms / MS_PER_SECOND, ",.1f") + " s";
		}
		return (ms >= WHOLE_MS_FROM) ? PythonFormat.format(ms, ",.0f") + " ms" : PythonFormat.format(ms, ".1f") + " ms";
	}

	/**
	 * A share of requests: one decimal, two when it is nearly all: {@code 98.7%},
	 * {@code 99.95%}.
	 * @param share the share, or {@code null}
	 * @return its text; {@code n/a} without one
	 */
	static String share(@Nullable Double share) {
		if (share == null) {
			return "n/a";
		}
		return PythonFormat.format(share, (share < NEARLY_ALL || share >= 1) ? ".1%" : ".2%");
	}

	/**
	 * A percentage without decimals: {@code 87%}.
	 * @param fraction the fraction
	 * @return its text
	 */
	static String percent(double fraction) {
		return PythonFormat.format(fraction, ".0%");
	}

	/**
	 * A percentage with one decimal: {@code 1.5%}.
	 * @param fraction the fraction
	 * @return its text
	 */
	static String percentToTenths(double fraction) {
		return PythonFormat.format(fraction, ".1%");
	}

	/**
	 * A count with thousands separators: {@code 12,345}.
	 * @param count the count
	 * @return its text
	 */
	static String count(long count) {
		return PythonFormat.format(count, ",");
	}

	/**
	 * A count of things, the noun agreeing with it: {@code 1 request},
	 * {@code 1,287 requests}, {@code 0 requests}. A phrase whose verb changes with the
	 * count too ("were") needs its own words.
	 * @param count the count
	 * @param noun what is counted, in the singular; its plural adds {@code s}
	 * @return the count with thousands separators, and the noun
	 */
	static String count(long count, String noun) {
		return count(count) + " " + noun + ((count == 1) ? "" : "s");
	}

	/**
	 * A whole figure with thousands separators: {@code 1,235}.
	 * @param value the figure
	 * @return its text, rounded half to even
	 */
	static String wholeWithSeparators(double value) {
		return PythonFormat.format(value, ",.0f");
	}

	/**
	 * A whole figure without separators: {@code 1235}.
	 * @param value the figure
	 * @return its text, rounded half to even
	 */
	static String wholeWithoutSeparators(double value) {
		return PythonFormat.format(value, ".0f");
	}

	/**
	 * A figure with one decimal: {@code 12.3}.
	 * @param value the figure
	 * @return its text
	 */
	static String tenths(double value) {
		return PythonFormat.format(value, ".1f");
	}

	/**
	 * A figure with two decimals: {@code 1.25}.
	 * @param value the figure
	 * @return its text
	 */
	static String hundredths(double value) {
		return PythonFormat.format(value, ".2f");
	}

	/**
	 * A text that may be missing, as an f-string writes it: the text, or {@code None}.
	 * @param text the text, or {@code null}
	 * @return the text, or {@code None}
	 */
	static String orNone(@Nullable String text) {
		return (text != null) ? text : "None";
	}

	/**
	 * A figure as Python's {@code g} writes it: {@code 1.5}, {@code 30}.
	 * @param value the figure
	 * @return its text
	 */
	static String general(double value) {
		return PythonFormat.format(value, "g");
	}

	/**
	 * A number as Python's {@code str} writes a count that may be missing: {@code 3}, or
	 * {@code None}.
	 * @param count the count, or {@code null}
	 * @return its text
	 */
	static String countOrNone(@Nullable Long count) {
		return (count != null) ? Long.toString(count) : "None";
	}

}
