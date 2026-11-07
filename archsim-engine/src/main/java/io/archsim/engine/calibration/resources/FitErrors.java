package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonRound;

/**
 * How the resource fits measure their error: at and above the median of what they were
 * fitted against, where a part is sized.
 */
final class FitErrors {

	private static final int ERROR_DIGITS = 4;

	private FitErrors() {
	}

	/**
	 * The points at or above the median.
	 * @param values the values
	 * @return the indexes of the values at or above their median, in order; none for no
	 * values
	 */
	static List<Integer> busyHalf(List<Double> values) {
		List<Integer> busy = new ArrayList<>();
		if (values.isEmpty()) {
			return busy;
		}
		double[] ordered = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		int middle = ordered.length / 2;
		double median = (ordered.length % 2 == 1) ? ordered[middle] : (ordered[middle - 1] + ordered[middle]) / 2;
		for (int i = 0; i < values.size(); i++) {
			if (values.get(i) >= median) {
				busy.add(i);
			}
		}
		return busy;
	}

	/**
	 * The largest relative error of fitted values against observed ones.
	 * @param comparisons each point's fitted and observed value
	 * @return the error, rounded to four places; {@code null} when no observed value is
	 * above 0
	 */
	static @Nullable Double maxRelative(List<Comparison> comparisons) {
		OptionalDouble largest = comparisons.stream()
			.filter((comparison) -> comparison.observed() > 0)
			.mapToDouble((comparison) -> Math.abs(comparison.fitted() - comparison.observed()) / comparison.observed())
			.max();
		return largest.isPresent() ? PythonRound.round(largest.getAsDouble(), ERROR_DIGITS) : null;
	}

	/**
	 * A fitted value and the one observed.
	 *
	 * @param fitted what the fit gives
	 * @param observed what was measured
	 */
	record Comparison(double fitted, double observed) {

	}

}
