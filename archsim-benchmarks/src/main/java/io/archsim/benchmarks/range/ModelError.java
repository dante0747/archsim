package io.archsim.benchmarks.range;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model's own error, by maximum likelihood (ADR-0094): each run's ln(measured /
 * predicted knee) is an error its experiment shares with its other runs, normal with mean
 * 0 and standard deviation {@code between}, plus one of its own, normal with mean 0 and
 * variance (its calibration spread)^2 + {@code within}^2. A run of an experiment the fit
 * has not seen errs by both, so its range takes {@link Fit#sigma()}. Each experiment's
 * runs count as one draw of its shared error, so an experiment with many runs weighs no
 * more than its evidence.
 */
final class ModelError {

	/** The largest error either part is searched to: a factor of e^2. */
	private static final double LARGEST = 2.0;

	/** The coarse grid both errors are searched on first, from 0. */
	private static final double COARSE = 0.005;

	/** The coarse grid's points, from 0 to {@link #LARGEST}. */
	private static final int COARSE_POINTS = (int) Math.round(LARGEST / COARSE) + 1;

	/** The fine grid the best coarse point is searched around. */
	private static final double FINE = 0.0005;

	/** How many fine steps either side of the best coarse point. */
	private static final int FINE_SPAN = 10;

	/** The least variance, so that a run without spread or error counts finitely. */
	private static final double LEAST_VARIANCE = 1e-12;

	private ModelError() {
	}

	/**
	 * The model's error.
	 * @param errors each run's ln(measured / predicted knee)
	 * @param spreads each run's calibration spread, in the same order
	 * @param experiments each run's experiment, in the same order
	 * @return the point of least deviance on a coarse grid, then on a fine grid around
	 * it; of equal points, the one with the least {@code between}, then the least
	 * {@code within}; 0 and 0 without runs. With one run per experiment the two parts
	 * cannot be told apart, and only {@link Fit#sigma()} means anything.
	 */
	static Fit of(List<Double> errors, List<Double> spreads, List<String> experiments) {
		if (errors.isEmpty()) {
			return new Fit(0.0, 0.0);
		}
		List<ExperimentRuns> groups = ExperimentRuns.of(errors, spreads, experiments);
		Fit coarse = search(groups, new Fit(0.0, 0.0), COARSE, COARSE_POINTS);
		Fit low = new Fit(Math.max(0.0, coarse.between() - FINE_SPAN * FINE),
				Math.max(0.0, coarse.within() - FINE_SPAN * FINE));
		return search(groups, low, FINE, 2 * FINE_SPAN + 1);
	}

	// the grid point of least deviance, of equal ones the least between, then the least
	// within; each experiment's sums at each within computed once
	private static Fit search(List<ExperimentRuns> groups, Fit from, double step, int points) {
		List<List<Sums>> byWithin = new ArrayList<>();
		for (int withinPoint = 0; withinPoint < points; withinPoint++) {
			double within = from.within() + withinPoint * step;
			byWithin.add(groups.stream().map((group) -> group.sums(within)).toList());
		}
		Fit best = from;
		double least = Double.POSITIVE_INFINITY;
		for (int betweenPoint = 0; betweenPoint < points; betweenPoint++) {
			double between = from.between() + betweenPoint * step;
			for (int withinPoint = 0; withinPoint < points; withinPoint++) {
				double deviance = deviance(byWithin.get(withinPoint), between * between);
				if (deviance < least) {
					least = deviance;
					best = new Fit(between, from.within() + withinPoint * step);
				}
			}
		}
		return best;
	}

	// the experiments' deviance at a shared variance
	private static double deviance(List<Sums> experiments, double shared) {
		double sum = 0.0;
		for (Sums experiment : experiments) {
			sum += experiment.deviance(shared);
		}
		return sum;
	}

	/**
	 * The model's error: one its experiment shares and one each run has.
	 *
	 * @param between the log standard deviation of the error an experiment's runs share
	 * @param within the log standard deviation of each run's own error, beyond its
	 * calibration spread
	 */
	record Fit(double between, double within) {

		/**
		 * The model's error for a run of an experiment the fit has not seen.
		 * @return the two errors in quadrature
		 */
		double sigma() {
			return Math.sqrt(this.between * this.between + this.within * this.within);
		}

	}

	/**
	 * One experiment's runs: their errors and calibration spreads.
	 *
	 * @param errors the runs' log errors
	 * @param spreads the runs' calibration spreads
	 */
	private record ExperimentRuns(List<Double> errors, List<Double> spreads) {

		static List<ExperimentRuns> of(List<Double> errors, List<Double> spreads, List<String> experiments) {
			Map<String, List<Integer>> runs = new LinkedHashMap<>();
			for (int run = 0; run < errors.size(); run++) {
				runs.computeIfAbsent(experiments.get(run), (name) -> new ArrayList<>()).add(run);
			}
			return runs.values()
				.stream()
				.map((indexes) -> new ExperimentRuns(indexes.stream().map(errors::get).toList(),
						indexes.stream().map(spreads::get).toList()))
				.toList();
		}

		// the sums over the runs that depend only on each run's own variance
		Sums sums(double within) {
			double logDeterminant = 0.0;
			double squares = 0.0;
			double sum = 0.0;
			double precision = 0.0;
			for (int run = 0; run < this.errors.size(); run++) {
				double spread = this.spreads.get(run);
				double variance = Math.max(spread * spread + within * within, LEAST_VARIANCE);
				double error = this.errors.get(run);
				logDeterminant += StrictMath.log(variance);
				squares += error * error / variance;
				sum += error / variance;
				precision += 1 / variance;
			}
			return new Sums(logDeterminant, squares, sum, precision);
		}

	}

	/**
	 * An experiment's runs summed at one {@code within}: with D their own variances, the
	 * log determinant of D, and the sums of error^2 / d, error / d and 1 / d.
	 *
	 * @param logDeterminant the sum of the runs' log variances
	 * @param squares the sum of each squared error over its variance
	 * @param sum the sum of each error over its variance
	 * @param precision the sum of the variances' inverses
	 */
	private record Sums(double logDeterminant, double squares, double sum, double precision) {

		/**
		 * The runs' deviance (twice their negative log likelihood, less a constant) as a
		 * normal with covariance D + shared J, J all ones: the log determinant by the
		 * matrix determinant lemma, the quadratic form by the Sherman-Morrison formula.
		 * @param shared the variance the runs share, {@code between}^2
		 * @return the deviance
		 */
		double deviance(double shared) {
			double coupling = 1 + shared * this.precision;
			return this.logDeterminant + StrictMath.log(coupling) + this.squares
					- shared * this.sum * this.sum / coupling;
		}

	}

}
