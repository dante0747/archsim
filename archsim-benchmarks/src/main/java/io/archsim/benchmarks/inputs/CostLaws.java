package io.archsim.benchmarks.inputs;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import io.archsim.engine.calibration.resources.LoadCurves;
import io.archsim.engine.calibration.resources.RatePoint;

/**
 * A {@link CostLaw} fitted to CPU per request measured at a few rates (ADR-0093): for
 * each exponent in hundredths up to 1, the floor and the shared part by least squares on
 * the relative error, the floor held at 0 where it would fall below; the exponent whose
 * fit errs least wins. No law is fitted where the cost does not fall with the rate.
 */
final class CostLaws {

	/** The fewest rates a law is fitted on: it has three numbers. */
	static final int MIN_RATES = 3;

	/** How many exponents are tried: hundredths from 0.01 to 1. */
	private static final int EXPONENTS = 100;

	private CostLaws() {
	}

	/**
	 * The law of the measured points.
	 * @param points each rate per replica and its CPU per request in milliseconds; points
	 * that are not finite, or without a rate or CPU, are left out
	 * @return the law; empty with fewer than {@link #MIN_RATES} rates, when the highest
	 * rate is under {@link LoadCurves#MIN_SPREAD} times the lowest, or when no law falls
	 * with the rate
	 */
	static Optional<CostLaw> fit(List<RatePoint> points) {
		List<RatePoint> usable = usable(points);
		if (usable.stream().map(RatePoint::rate).distinct().count() < MIN_RATES
				|| usable.getLast().rate() < LoadCurves.MIN_SPREAD * usable.getFirst().rate()) {
			return Optional.empty();
		}
		return IntStream.rangeClosed(1, EXPONENTS)
			.mapToObj((step) -> Candidate.at((double) step / EXPONENTS, usable))
			.flatMap(Optional::stream)
			.min(Comparator.comparingDouble(Candidate::error))
			.map(Candidate::law);
	}

	/**
	 * The points a law can be fitted on, by rising rate.
	 * @param points the points
	 * @return those with a finite rate and CPU per request above 0
	 */
	static List<RatePoint> usable(List<RatePoint> points) {
		return points.stream()
			.filter((point) -> Double.isFinite(point.rate()) && Double.isFinite(point.value()) && point.rate() > 0
					&& point.value() > 0)
			.sorted(Comparator.comparingDouble(RatePoint::rate))
			.toList();
	}

	/**
	 * The law at one exponent and how far it errs.
	 *
	 * @param law the law
	 * @param error the sum of its squared relative errors
	 */
	private record Candidate(CostLaw law, double error) {

		/**
		 * The floor and the shared part at an exponent, by least squares on the relative
		 * error: each point weighted by its cost's inverse square.
		 * @param exponent the exponent
		 * @param points the points
		 * @return the fit; empty when its shared part is not above 0
		 */
		static Optional<Candidate> at(double exponent, List<RatePoint> points) {
			Sums sums = Sums.of(exponent, points);
			double shared = sums.sharedPart();
			double floor = (sums.costs() - shared * sums.falls()) / sums.weights();
			if (floor < 0) {
				floor = 0.0;
				shared = sums.fallCosts() / sums.fallSquares();
			}
			if (!(shared > 0) || !Double.isFinite(shared)) {
				return Optional.empty();
			}
			CostLaw law = new CostLaw(floor, shared, exponent);
			double error = points.stream().mapToDouble((point) -> {
				double relative = law.msAt(point.rate()) / point.value() - 1;
				return relative * relative;
			}).sum();
			return Optional.of(new Candidate(law, error));
		}

	}

	/**
	 * The weighted sums of a least-squares fit of the cost on each point's fall,
	 * {@code rate^-exponent}.
	 *
	 * @param weights the weights' sum
	 * @param falls the weighted falls' sum
	 * @param costs the weighted costs' sum
	 * @param fallSquares the weighted squared falls' sum
	 * @param fallCosts the weighted products of fall and cost, summed
	 */
	private record Sums(double weights, double falls, double costs, double fallSquares, double fallCosts) {

		static Sums of(double exponent, List<RatePoint> points) {
			double weights = 0;
			double falls = 0;
			double costs = 0;
			double fallSquares = 0;
			double fallCosts = 0;
			for (RatePoint point : points) {
				double weight = 1 / (point.value() * point.value());
				double fall = StrictMath.pow(point.rate(), -exponent);
				weights += weight;
				falls += weight * fall;
				costs += weight * point.value();
				fallSquares += weight * fall * fall;
				fallCosts += weight * fall * point.value();
			}
			return new Sums(weights, falls, costs, fallSquares, fallCosts);
		}

		// the shared part with the floor free
		double sharedPart() {
			return (this.weights * this.fallCosts - this.falls * this.costs)
					/ (this.weights * this.fallSquares - this.falls * this.falls);
		}

	}

}
