package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.archsim.engine.numeric.TrueDivision;

/**
 * How each figure of a design changed from its baseline's ({@code regressions}): the p95
 * latency, the success rate and the monthly cost, and the breaking point when both were
 * swept.
 */
public final class Regressions {

	/** How far past one step a breaking point must drop, for rounding. */
	private static final double STEP_ROUNDING = 1e-9;

	private Regressions() {
	}

	/**
	 * Each figure's change from the baseline, the ones neither side has left out.
	 * @param current this change's measurement
	 * @param baseline the baseline's
	 * @param thresholds how much worse each figure may get
	 * @return the rows, in the order p95, success rate, cost, breaking point
	 */
	public static List<Regression> between(Measurement current, Measurement baseline, Thresholds thresholds) {
		List<Regression> rows = new ArrayList<>();
		Regression.increase(Metric.P95, new Regression.Figures(baseline.p95(), current.p95()), thresholds.p95Increase())
			.ifPresent(rows::add);
		Regression
			.successDrop(new Regression.Figures(baseline.successRate(), current.successRate()),
					thresholds.successDrop())
			.ifPresent(rows::add);
		Regression
			.increase(Metric.COST_MONTHLY, new Regression.Figures(baseline.costMonthly(), current.costMonthly()),
					thresholds.costIncrease())
			.ifPresent(rows::add);
		if (current.kneeRange() != null && baseline.kneeRange() != null) {
			rows.add(knee(current, baseline, thresholds.kneeDrop()));
		}
		return List.copyOf(rows);
	}

	/**
	 * How the breaking point changed ({@code _knee_change}). Each is known to one step of
	 * the load grid it was read from, so only a drop past the threshold and of more than
	 * a step regressed; so did a design that now misses its objectives at the lightest
	 * load.
	 * @param current this change's measurement, swept
	 * @param baseline the baseline's, swept
	 * @param threshold how far the breaking point may drop, as a share
	 * @return the row, with the grid's step and lightest load
	 */
	private static Regression knee(Measurement current, Measurement baseline, double threshold) {
		Double now = current.knee();
		Double before = baseline.knee();
		double step = Math.max(Objects.requireNonNullElse(current.kneeResolution(), 0.0),
				Objects.requireNonNullElse(baseline.kneeResolution(), 0.0));
		Double change = null;
		boolean regressed;
		if (now != null && before != null) {
			change = TrueDivision.of(now - before, before);
			regressed = change < -threshold && before - now > step * (1 + STEP_ROUNDING);
		}
		else {
			regressed = before != null;
		}
		double lightest = Objects.requireNonNull(current.kneeRange()).getFirst();
		return new Regression(Metric.KNEE, Metric.KNEE.label(), before, now, Regression.rounded(change), threshold,
				regressed, step, lightest);
	}

}
