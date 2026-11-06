package io.archsim.engine.check;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * How one figure changed from the baseline, and whether it regressed.
 *
 * @param metric the figure
 * @param label what the check calls it
 * @param baseline the baseline's figure; {@code null} when it has none
 * @param current this change's figure; {@code null} when it has none
 * @param change the change, relative or absolute as the figure's threshold is, to six
 * decimals; {@code null} when it cannot be worked out
 * @param threshold how much worse the figure may get
 * @param regressed whether it got worse by more than that
 * @param resolution the step of the load grid a breaking point was read from; left out
 * for other figures
 * @param lightest the lightest load the grid swept; left out for other figures
 */
public record Regression(Metric metric, String label, @Nullable Double baseline, @Nullable Double current,
		@Nullable Double change, double threshold, boolean regressed, @OmitWhenDefault @Nullable Double resolution,
		@OmitWhenDefault @Nullable Double lightest) {

	/** The decimals a change is rounded to. */
	private static final int CHANGE_DECIMALS = 6;

	/**
	 * A figure that regresses when it rises by more than a share of the baseline's, as
	 * the p95 latency and the monthly cost do.
	 * @param metric the figure
	 * @param figures the baseline's figure and this change's
	 * @param threshold the share it may rise by
	 * @return the row; empty when either figure is missing or the baseline's is zero
	 */
	static Optional<Regression> increase(Metric metric, Figures figures, double threshold) {
		Double current = figures.current();
		Double baseline = figures.baseline();
		if (current == null || baseline == null || baseline == 0) {
			return Optional.empty();
		}
		double change = (current - baseline) / baseline;
		return Optional.of(new Regression(metric, metric.label(), baseline, current, rounded(change), threshold,
				change > threshold, null, null));
	}

	/**
	 * The success rate, which regresses when it drops by more than a share of all
	 * requests.
	 * @param figures the baseline's success rate and this change's
	 * @param threshold how far it may drop
	 * @return the row; empty when either figure is missing
	 */
	static Optional<Regression> successDrop(Figures figures, double threshold) {
		Double current = figures.current();
		Double baseline = figures.baseline();
		if (current == null || baseline == null) {
			return Optional.empty();
		}
		double change = current - baseline;
		return Optional.of(new Regression(Metric.SUCCESS_RATE, Metric.SUCCESS_RATE.label(), baseline, current,
				rounded(change), threshold, change < -threshold, null, null));
	}

	/**
	 * A change rounded as Python's {@code round(change, 6)} rounds it.
	 * @param change the change; {@code null} when it cannot be worked out
	 * @return the change, rounded
	 */
	static @Nullable Double rounded(@Nullable Double change) {
		return (change != null) ? PythonRound.round(change, CHANGE_DECIMALS) : null;
	}

	/**
	 * A figure in the baseline and in this change.
	 *
	 * @param baseline the baseline's; {@code null} when it has none
	 * @param current this change's; {@code null} when it has none
	 */
	record Figures(@Nullable Double baseline, @Nullable Double current) {
	}

}
