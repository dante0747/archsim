package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.model.text.PythonFormat;

/**
 * A service's CPU per request as a law of the rate one replica gets (ADR-0093): a floor
 * of work every request needs, and a part the requests share that falls as they come
 * closer together (waking an idle CPU, timers, cold caches):
 * {@code floorMs + sharedMs × rate^-exponent}. It never rises with the rate. With an
 * exponent of 1 at most, the CPU the shared part takes each second never falls as the
 * rate rises, so neither does a replica's busy time.
 *
 * @param floorMs the work every request needs, in milliseconds; 0 or more
 * @param sharedMs the shared part at one request per second, in milliseconds; above 0
 * @param exponent how fast the shared part falls with the rate; above 0, and 1 at most
 */
record CostLaw(double floorMs, double sharedMs, double exponent) {

	/**
	 * How far above the highest rate it was fitted on a law is carried: twice that rate.
	 * Carried as far as the CPUs fill from staircases that stopped at a third of the
	 * core, the law charged the busiest measured steps 28% too little (ADR-0093).
	 */
	static final double FURTHEST = 2.0;

	/** How far apart in rate a law's factors are: a quarter. */
	private static final double FACTOR_STEP = 1.25;

	/**
	 * How near the first or the last rate a factor's rate may be: half a step, in
	 * log(rate). A nearer one would be a second point almost where the first or the last
	 * is.
	 */
	private static final double END_GAP = Math.sqrt(FACTOR_STEP);

	private static final double MS_PER_S = 1000.0;

	/** How many halvings find where the CPUs fill. */
	private static final int HALVINGS = 60;

	/** How the words write a number. */
	private static final String TEXT_FORMAT = ".4g";

	/**
	 * Checks that the law never rises and that its busy time never falls.
	 */
	CostLaw {
		if (!(floorMs >= 0) || !Double.isFinite(floorMs)) {
			throw new IllegalArgumentException("a cost law's floor is a number of milliseconds, 0 or more: " + floorMs);
		}
		if (!(sharedMs > 0) || !Double.isFinite(sharedMs)) {
			throw new IllegalArgumentException("a cost law's shared part is above 0: " + sharedMs);
		}
		if (!(exponent > 0) || exponent > 1) {
			throw new IllegalArgumentException("a cost law's exponent is above 0 and 1 at most: " + exponent);
		}
	}

	/**
	 * The CPU per request at a rate.
	 * @param rps the requests per second one replica gets, above 0
	 * @return the milliseconds
	 */
	double msAt(double rps) {
		return this.floorMs + this.sharedMs * StrictMath.pow(rps, -this.exponent);
	}

	/**
	 * The rate at which one replica's CPUs fill: where the rate times the CPU per request
	 * is a thousand milliseconds a second for each CPU.
	 * @param cores the replica's CPUs
	 * @return the rate; empty without CPUs, or when the busy time grows too slowly to
	 * fill them at any rate a double holds (no floor and an exponent of 1 or nearly)
	 */
	OptionalDouble fillRps(double cores) {
		if (!(cores > 0)) {
			return OptionalDouble.empty();
		}
		double budget = MS_PER_S * cores;
		double low = 0.0;
		double high = 1.0;
		while (busyMs(high) < budget) {
			low = high;
			high *= 2;
			if (!Double.isFinite(high)) {
				return OptionalDouble.empty();
			}
		}
		for (int i = 0; i < HALVINGS; i++) {
			double middle = (low + high) / 2;
			if (busyMs(middle) < budget) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		return OptionalDouble.of(high);
	}

	/**
	 * Where a curve of the law ends above the highest rate it was fitted on, and why:
	 * where one replica's CPUs fill, but no further than {@link #FURTHEST} times that
	 * rate, and not below it.
	 * @param topRps the highest rate the law was fitted on
	 * @param cores the replica's CPUs
	 * @return the end
	 */
	End endAbove(double topRps, double cores) {
		double furthest = FURTHEST * topRps;
		OptionalDouble fill = fillRps(cores);
		if (fill.isEmpty() || fill.getAsDouble() >= furthest) {
			return new End(furthest, Reach.FURTHEST);
		}
		if (fill.getAsDouble() <= topRps) {
			return new End(topRps, Reach.TOP);
		}
		return new End(fill.getAsDouble(), Reach.FILL);
	}

	/**
	 * The law as service-time factors, each rate's CPU per request over the reference
	 * rate's: the first rate, the reference rate, the last rate, and between them the
	 * reference rate times whole powers of 1.25 (a quarter apart) that are at least half
	 * a step from the first and the last.
	 * @param fromRps the first rate
	 * @param referenceRps the rate whose CPU per request a factor of 1 stands for, from
	 * the first to the last
	 * @param toRps the last rate
	 * @return the rates and factors, by rising rate
	 * @throws IllegalArgumentException when the reference is not from the first to the
	 * last rate
	 */
	List<RatePoint> factors(double fromRps, double referenceRps, double toRps) {
		if (!(fromRps <= referenceRps && referenceRps <= toRps)) {
			throw new IllegalArgumentException(
					"a reference rate of " + referenceRps + " is not from " + fromRps + " to " + toRps);
		}
		List<Double> rates = new ArrayList<>(List.of(fromRps));
		rates.addAll(below(referenceRps, fromRps * END_GAP));
		rates.add(referenceRps);
		rates.addAll(above(referenceRps, toRps / END_GAP));
		rates.add(toRps);
		double reference = msAt(referenceRps);
		return rates.stream().distinct().map((rate) -> new RatePoint(rate, msAt(rate) / reference)).toList();
	}

	/**
	 * The law in words.
	 * @return the floor, the shared part and the exponent, as a formula in milliseconds
	 */
	String describe() {
		return PythonFormat.format(this.floorMs, TEXT_FORMAT) + " + " + PythonFormat.format(this.sharedMs, TEXT_FORMAT)
				+ " × rate^-" + PythonFormat.format(this.exponent, TEXT_FORMAT) + " ms";
	}

	// the reference rate divided by whole powers of the step, down to above a rate, by
	// rising rate
	private static List<Double> below(double referenceRps, double lowestRps) {
		List<Double> rates = new ArrayList<>();
		for (int power = 1; referenceRps / StrictMath.pow(FACTOR_STEP, power) > lowestRps; power++) {
			rates.add(referenceRps / StrictMath.pow(FACTOR_STEP, power));
		}
		return rates.reversed();
	}

	// the reference rate times whole powers of the step, up to below a rate
	private static List<Double> above(double referenceRps, double highestRps) {
		List<Double> rates = new ArrayList<>();
		for (int power = 1; referenceRps * StrictMath.pow(FACTOR_STEP, power) < highestRps; power++) {
			rates.add(referenceRps * StrictMath.pow(FACTOR_STEP, power));
		}
		return rates;
	}

	private double busyMs(double rps) {
		return rps * msAt(rps);
	}

	/**
	 * Why a curve of the law ends where it does.
	 */
	enum Reach {

		/** One replica's CPUs fill there. */
		FILL,

		/** {@link CostLaw#FURTHEST} times the top rate, short of where the CPUs fill. */
		FURTHEST,

		/** The top rate itself, which had filled the CPUs. */
		TOP

	}

	/**
	 * Where a curve of the law ends, and why.
	 *
	 * @param rps the rate
	 * @param reach why there
	 */
	record End(double rps, Reach reach) {

	}

}
