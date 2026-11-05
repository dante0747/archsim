package io.archsim.engine.compare;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.RunSummary;

/**
 * How one run figure changed from the first run to the second.
 *
 * @param key the figure, as the report's summary names it, such as {@code latencyMs.p95}
 * @param label what the comparison calls it
 * @param unit its unit
 * @param better which way it is better
 * @param a the figure in the first run; {@code null} when that run did not measure it
 * @param b the figure in the second run; {@code null} when that run did not measure it
 * @param delta the second minus the first; {@code null} unless both runs measured it
 * @param relativeDelta the delta as a share of the first figure; {@code null} unless the
 * first figure is above or below zero
 * @param verdict how it changed
 */
public record KpiChange(String key, String label, Unit unit, Better better, @Nullable Double a, @Nullable Double b,
		@Nullable Double delta, @Nullable Double relativeDelta, Verdict verdict) {

	/** A relative change smaller than this is about the same. */
	private static final double NOISE = 0.03;

	/** An absolute change smaller than this is the same. */
	private static final double NO_CHANGE = 1e-9;

	/**
	 * How a figure changed between two runs ({@code _verdict}).
	 * @param kpi the figure
	 * @param first the first run's summary
	 * @param second the second run's summary
	 * @return the change
	 */
	static KpiChange of(Kpi kpi, RunSummary first, RunSummary second) {
		Double before = kpi.of(first);
		Double after = kpi.of(second);
		if (before == null || after == null) {
			return new KpiChange(kpi.key(), kpi.label(), kpi.unit(), kpi.better(), before, after, null, null,
					Verdict.NOT_AVAILABLE);
		}
		double delta = after - before;
		// from zero, the relative change is infinite (or none, when nothing changed)
		Double relative = (before != 0) ? delta / Math.abs(before) : null;
		boolean relativelySmall = relative != null && Math.abs(relative) < NOISE;
		boolean small = relativelySmall || Math.abs(delta) < NO_CHANGE;
		return new KpiChange(kpi.key(), kpi.label(), kpi.unit(), kpi.better(), before, after, delta, relative,
				verdict(kpi.better(), delta, small));
	}

	private static Verdict verdict(Better better, double delta, boolean small) {
		if (small) {
			return Verdict.SAME;
		}
		return switch (better) {
			case NEUTRAL -> Verdict.CHANGED;
			case HIGHER -> (delta > 0) ? Verdict.BETTER : Verdict.WORSE;
			case LOWER -> (delta < 0) ? Verdict.BETTER : Verdict.WORSE;
		};
	}

}
