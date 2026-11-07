package io.archsim.engine.calibration.resources;

import java.util.List;
import java.util.function.IntToDoubleFunction;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;

/**
 * The least-squares slope of one series on another, and its standard error, as the Python
 * engine's {@code _ols} fits it.
 *
 * @param value the slope
 * @param error its standard error; {@code null} without degrees of freedom left or
 * without spread in the first series
 */
record Slope(double value, @Nullable Double error) {

	/**
	 * The slope of {@code ys} on {@code xs}.
	 * @param xs the first series
	 * @param ys the second series, as long
	 * @return the slope: 0 without spread in {@code xs}, its error {@code null} then and
	 * with two points or fewer
	 */
	static Slope of(List<Double> xs, List<Double> ys) {
		int count = xs.size();
		double meanX = sum(count, xs::get) / count;
		double meanY = sum(count, ys::get) / count;
		double spread = sum(count, (index) -> (xs.get(index) - meanX) * (xs.get(index) - meanX));
		if (spread <= 0) {
			return new Slope(0.0, null);
		}
		double slope = sum(count, (index) -> (xs.get(index) - meanX) * (ys.get(index) - meanY)) / spread;
		if (count <= 2) {
			return new Slope(slope, null);
		}
		double squaredErrors = sum(count, (index) -> {
			double residual = ys.get(index) - (meanY + slope * (xs.get(index) - meanX));
			return residual * residual;
		});
		return new Slope(slope, Math.sqrt(squaredErrors / (count - 2) / spread));
	}

	private static double sum(int count, IntToDoubleFunction term) {
		return FloatSum.of(IntStream.range(0, count).mapToDouble(term));
	}

}
