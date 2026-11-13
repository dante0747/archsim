package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The median WAL flush with which the model's writes wait as long on average for their
 * row lock and their flush as the service's did, at the pass's rate and mix. What the
 * service measured as that wait (synchronous_commit on minus off) already includes the
 * other commits' flushes it queued behind and, on one row, the writes ahead of it holding
 * the lock; the model adds both again, so the measured wait itself is too long a flush.
 * Engine 0.14.0 used it: on Kafka's write pass the model's commits then waited 1.09 ms
 * where the service's had waited 0.92.
 */
final class FlushFit {

	/** The least simulated seconds of PostgreSQL alone. */
	private static final double LEAST_SECONDS = CommitFlush.FIT_SECONDS;

	/** The most simulated seconds. */
	private static final double MOST_SECONDS = 120;

	/**
	 * Enough commits that their flushes' mean is the distribution's, not one sample's.
	 */
	private static final double COMMITS = 1000;

	private static final int ROUNDS = 12;

	private static final double CLOSE_ENOUGH = 2e-3;

	private static final double LEAST_SHAPE = 1.01;

	private static final double LEAST_WRITES = 1e-9;

	private final DatabaseAlone database;

	FlushFit(DatabaseAlone database) {
		this.database = database;
	}

	/**
	 * The fitted median flush and the model's database there.
	 * @param experiment the experiment
	 * @param values the database's other inputs' values
	 * @param target the pass's rate and mix, and the wait the service measured
	 * @return the median flush and the database's figures
	 */
	Result fit(Experiment experiment, ObjectNode values, Target target) {
		double sigma = StrictMath.log(Math.max(target.shape(), LEAST_SHAPE)) / ModelDistributions.Z95;
		// a flush alone, with nothing to wait for
		double median = target.waitMs() / StrictMath.exp(sigma * sigma / 2);
		double writes = target.rate().doubleValue() * (1 - target.readRatio());
		double seconds = Math.min(MOST_SECONDS, Math.max(LEAST_SECONDS, COMMITS / Math.max(writes, LEAST_WRITES)));
		// the wait only grows with the flush: keep the answer bracketed
		double low = 0.0;
		double high = Double.POSITIVE_INFINITY;
		JsonNode db = PythonValues.none();
		for (int round = 0; round < ROUNDS; round++) {
			ObjectNode flush = ModelDistributions.lognormal(median, median * target.shape());
			db = this.database.simulate(experiment, CommitFlush.with(values, flush), target.rate(),
					new DatabaseAlone.Mix(target.readRatio(), seconds));
			double waited = PythonValues.numberOrZero(db, "meanLockWaitMs")
					+ PythonValues.numberOrZero(db, "meanCommitWaitMs");
			if (waited <= 0 || Math.abs(waited / target.waitMs() - 1) < CLOSE_ENOUGH) {
				break;
			}
			if (waited > target.waitMs()) {
				high = median;
			}
			else {
				low = median;
			}
			double next = median * target.waitMs() / waited;
			median = (low < next && next < high) ? next : nextBisection(low, high, median);
		}
		return new Result(median, db);
	}

	private static double nextBisection(double low, double high, double median) {
		return (high < Double.POSITIVE_INFINITY) ? (low + high) / 2 : 2 * median;
	}

	/**
	 * What the fit aims at.
	 *
	 * @param rate the pass's rate
	 * @param readRatio the share of reads in its mix
	 * @param waitMs the mean wait for the lock and the flush the service measured
	 * @param shape the wait's p95 over its median
	 */
	record Target(JsonNode rate, double readRatio, double waitMs, double shape) {

	}

	/**
	 * The fit's answer.
	 *
	 * @param medianMs the median flush
	 * @param database the model's database there: commits per flush, lock and commit
	 * waits
	 */
	record Result(double medianMs, JsonNode database) {

	}

}
