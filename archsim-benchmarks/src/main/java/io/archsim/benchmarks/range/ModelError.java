package io.archsim.benchmarks.range;

import java.util.ArrayList;
import java.util.List;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The model's own error, by maximum likelihood: each run's ln(measured / predicted knee)
 * a normal with mean 0 and variance (its calibration spread)^2 + s^2; 0 when the
 * calibration spread alone explains the errors.
 */
final class ModelError {

	/** The grid the likelihood is searched on, from 0. */
	private static final double GRID = 0.0005;

	/** The grid's points: up to 2, a factor of e^2. */
	private static final int POINTS = 4001;

	/** The least variance, so that a run without spread or error counts finitely. */
	private static final double LEAST_VARIANCE = 1e-12;

	private ModelError() {
	}

	/**
	 * The model's error.
	 * @param errors each run's ln(measured / predicted knee)
	 * @param spreads each run's calibration spread, in the same order
	 * @return the grid's point of least negative log likelihood, the first of equal ones;
	 * 0 without runs
	 */
	static double sigma(List<Double> errors, List<Double> spreads) {
		if (errors.isEmpty()) {
			return 0.0;
		}
		double best = 0.0;
		double least = Double.POSITIVE_INFINITY;
		for (int point = 0; point < POINTS; point++) {
			double sigma = point * GRID;
			double likelihood = negativeLogLikelihood(errors, spreads, sigma);
			if (likelihood < least) {
				least = likelihood;
				best = sigma;
			}
		}
		return best;
	}

	// added up as Python's sum adds floats
	private static double negativeLogLikelihood(List<Double> errors, List<Double> spreads, double sigma) {
		List<Double> terms = new ArrayList<>();
		for (int run = 0; run < errors.size(); run++) {
			double spread = spreads.get(run);
			double variance = Math.max(spread * spread + sigma * sigma, LEAST_VARIANCE);
			double error = errors.get(run);
			terms.add(StrictMath.log(variance) + error * error / variance);
		}
		return PythonValues.sumOfFloats(terms);
	}

}
