package io.archsim.benchmarks.report;

import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;

/**
 * Numbers as the validation page writes them, with Python's format specs: a relative
 * error as a whole percentage, a latency to two significant places, a load with thousands
 * separators, and None as a dash.
 */
final class Figures {

	/** What the page writes for a number it does not have. */
	static final String NONE = "-";

	/** A share's percentage is the share times this. */
	static final int PERCENT = 100;

	private static final double LARGE_LATENCY_MS = 100;

	private static final double MEDIUM_LATENCY_MS = 10;

	private static final double MIB_PER_GIB = 1024;

	private static final int DATE_LENGTH = 10;

	private Figures() {
	}

	/**
	 * The date of a timestamp, Python's {@code at[:10]}.
	 * @param timestamp an ISO timestamp, {@code 2026-11-02T03:20:00Z}
	 * @return its first ten characters, {@code 2026-11-02}
	 */
	static String date(String timestamp) {
		return timestamp.substring(0, Math.min(timestamp.length(), DATE_LENGTH));
	}

	/**
	 * {@code format(value, spec)}: an integer formatted as Python formats an int, a float
	 * as a float.
	 * @param value a number
	 * @param spec the format spec, as Python's f-strings write it
	 * @return the number's text
	 */
	static String format(JsonNode value, String spec) {
		return value.isIntegralNumber() ? PythonFormat.format(value.longValue(), spec)
				: PythonFormat.format(value.doubleValue(), spec);
	}

	/**
	 * A signed share as a whole percentage: {@code +16%}, {@code -5%}, and never
	 * {@code -0%}.
	 * @param share the share, or None
	 * @return the percentage, or a dash
	 */
	static String percent(JsonNode share) {
		if (PythonValues.isNone(share)) {
			return NONE;
		}
		double hundredths = share.doubleValue() * PERCENT;
		return (Math.rint(hundredths) == 0) ? "0%" : PythonFormat.format(hundredths, "+.0f") + "%";
	}

	/**
	 * A share as a whole percentage without a sign of its own, a magnitude: {@code 16%}.
	 * @param share the share, or None
	 * @return the percentage, or a dash
	 */
	static String magnitude(JsonNode share) {
		return PythonValues.isNone(share) ? NONE : PythonFormat.format(share.doubleValue() * PERCENT, ".0f") + "%";
	}

	/**
	 * A latency in milliseconds: {@code 1,234}, {@code 12.3} or {@code 1.23}.
	 * @param latency the latency, or None
	 * @return its text, or a dash
	 */
	static String milliseconds(JsonNode latency) {
		if (PythonValues.isNone(latency)) {
			return NONE;
		}
		double value = latency.doubleValue();
		if (value >= LARGE_LATENCY_MS) {
			return format(latency, ",.0f");
		}
		return format(latency, (value >= MEDIUM_LATENCY_MS) ? ".1f" : ".2f");
	}

	/**
	 * A load in requests per second: {@code 1,234}.
	 * @param rate the load, or None
	 * @return its text, or a dash
	 */
	static String rate(JsonNode rate) {
		return PythonValues.isNone(rate) ? NONE : format(rate, ",.0f");
	}

	/**
	 * Busy cores: {@code 0.79}.
	 * @param cores the busy cores, or None
	 * @return their text, or a dash
	 */
	static String cores(JsonNode cores) {
		return PythonValues.isNone(cores) ? NONE : format(cores, ".2f");
	}

	/**
	 * A working set: {@code 148 MiB}, or {@code 1.5 GiB}.
	 * @param mebibytes the working set in MiB, or None
	 * @return its text, or a dash
	 */
	static String memory(JsonNode mebibytes) {
		if (PythonValues.isNone(mebibytes)) {
			return NONE;
		}
		if (mebibytes.doubleValue() >= MIB_PER_GIB) {
			return PythonFormat.format(mebibytes.doubleValue() / MIB_PER_GIB, ".1f") + " GiB";
		}
		return format(mebibytes, ",.0f") + " MiB";
	}

	/**
	 * Items as a sentence lists them: {@code a, b and c}.
	 * @param items the items, at least one
	 * @return the list
	 */
	static String andList(List<String> items) {
		if (items.size() == 1) {
			return items.getFirst();
		}
		return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
	}

	/**
	 * Loads as a sentence lists them: {@code 1,000, 2,000 and 3,000 req/s}.
	 * @param rates the loads, at least one
	 * @return the list
	 */
	static String rates(List<JsonNode> rates) {
		return andList(rates.stream().map(Figures::rate).toList()) + " req/s";
	}

	/**
	 * A knee: its load, or how it lies beyond the ladder ({@code > 9,458}).
	 * @param knee a knee, as a comparison holds it
	 * @return its text
	 */
	static String knee(JsonNode knee) {
		JsonNode rate = PythonValues.get(knee, "kneeRps");
		if (!PythonValues.isNone(rate)) {
			return rate(rate);
		}
		String bound = PythonValues.get(knee, "bound").stringValue("");
		if ("above".equals(bound)) {
			return "> " + rate(PythonValues.get(knee, "aboveRps"));
		}
		return "below".equals(bound) ? "< " + rate(PythonValues.get(knee, "belowRps")) : NONE;
	}

}
