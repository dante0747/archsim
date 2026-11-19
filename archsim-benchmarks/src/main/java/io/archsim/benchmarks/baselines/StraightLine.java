package io.archsim.benchmarks.baselines;

import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * A straight line fitted to points by least squares.
 *
 * @param intercept its value at zero
 * @param slope how much it rises per unit
 * @param rSquared the share of the points' variance it explains: 1 for points on a line
 */
record StraightLine(double intercept, double slope, double rSquared) {

	/**
	 * The least squares line through some points.
	 * @param points the points
	 * @return the line; empty when the points are all at one place, or there are none
	 */
	static Optional<StraightLine> through(List<Point> points) {
		double meanAt = mean(points, Point::at);
		double meanValue = mean(points, Point::value);
		double spreadAt = sum(points, (point) -> (point.at() - meanAt) * (point.at() - meanAt));
		if (points.isEmpty() || spreadAt == 0) {
			return Optional.empty();
		}
		double spreadValue = sum(points, (point) -> (point.value() - meanValue) * (point.value() - meanValue));
		double together = sum(points, (point) -> (point.at() - meanAt) * (point.value() - meanValue));
		double slope = together / spreadAt;
		double rSquared = (spreadValue == 0) ? 1.0 : together * together / (spreadAt * spreadValue);
		return Optional.of(new StraightLine(meanValue - slope * meanAt, slope, rSquared));
	}

	/**
	 * Where the line reaches a value.
	 * @param value the value
	 * @return the place at which the line has it
	 */
	double reaches(double value) {
		return (value - this.intercept) / this.slope;
	}

	private static double mean(List<Point> points, ToDoubleFunction<Point> coordinate) {
		return points.stream().mapToDouble(coordinate).average().orElse(0.0);
	}

	private static double sum(List<Point> points, ToDoubleFunction<Point> term) {
		return points.stream().mapToDouble(term).sum();
	}

	/**
	 * A point a line is fitted to.
	 *
	 * @param at where it is
	 * @param value its value there
	 */
	record Point(double at, double value) {

	}

}
