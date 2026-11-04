package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

import io.archsim.model.text.PythonFormat;

/** The estimate's one-line summary, word for word as the Python engine writes it. */
final class EstimateText {

	/** From this many milliseconds a time reads in seconds. */
	private static final double MILLIS_PER_SECOND = 1000.0;

	/** From this many milliseconds a time reads without decimals. */
	private static final double WHOLE_MS = 10.0;

	private EstimateText() {
	}

	/**
	 * The summary.
	 * @param peak the peak load, in requests per second
	 * @param saturation what limits the design
	 * @param latency the latency, when it is estimated
	 * @return the summary: what overloads first and where it saturates, or the p95 and
	 * the busiest part
	 * @throws IllegalStateException when a rate-limited part comes first among parts that
	 * all use none of their limits at the peak, where the Python engine raises a
	 * {@code TypeError} (it has no saturation point to write)
	 */
	static String summary(double peak, Saturation saturation, @Nullable Latency latency) {
		EstimatedPart busiest = saturation.busiest();
		if (busiest == null) {
			return "No estimate: no part with known capacity carries load.";
		}
		if (!saturation.overloaded().isEmpty()) {
			return overloaded(peak, saturation);
		}
		Double p95 = (latency != null) ? latency.p95() : null;
		String lead = (p95 != null) ? "p95 ≈ " + ms(p95) + "; " : "";
		String summary = lead + "busiest " + busiest.label() + " "
				+ PythonFormat.format(Saturation.orZero(busiest.utilization()), ".0%");
		EstimatedPart first = saturation.firstLimited();
		if (first != null && !first.id().equals(busiest.id()) && first.limitedBy() == Limit.RATE_LIMIT) {
			summary += "; " + first.label() + " uses " + PythonFormat.format(Saturation.orZero(first.load()), ".0%")
					+ " of its rate limit (turns requests away above about "
					+ PythonFormat.format(saturationOrThrow(saturation.rps()), ",.0f") + " req/s)";
		}
		return summary;
	}

	/**
	 * A time as the summary reads it: seconds from a second, whole milliseconds from 10.
	 * @param ms the time, in milliseconds
	 * @return {@code 1.25 s}, {@code 97 ms} or {@code 4.2 ms}
	 */
	static String ms(double ms) {
		if (ms >= MILLIS_PER_SECOND) {
			return PythonFormat.format(ms / MILLIS_PER_SECOND, ".2f") + " s";
		}
		return (ms >= WHOLE_MS) ? PythonFormat.format(ms, ".0f") + " ms" : PythonFormat.format(ms, ".1f") + " ms";
	}

	/**
	 * The summary of an overloaded design: the part that needs most of its capacity.
	 * @param peak the peak load
	 * @param saturation what limits the design, with an overloaded part
	 * @return the summary
	 */
	private static String overloaded(double peak, Saturation saturation) {
		EstimatedPart worst = Saturation.firstOfLargest(saturation.overloaded(),
				(part) -> Saturation.orZero(part.load()));
		Double load = worst.load();
		String need = (load != null) ? "needs " + PythonFormat.format(load, ".0%") + " of its capacity"
				: "cannot keep up";
		Double rps = saturation.rps();
		String saturates = (rps != null && rps != 0)
				? "; it saturates at about " + PythonFormat.format(rps, ",.0f") + " req/s." : ".";
		return "Overloaded at " + PythonFormat.format(peak, ",.0f") + " req/s: " + worst.label() + " " + need
				+ saturates;
	}

	private static double saturationOrThrow(@Nullable Double saturation) {
		if (saturation == null) {
			throw new IllegalStateException("a rate limit in use at no load has no saturation point");
		}
		return saturation;
	}

}
