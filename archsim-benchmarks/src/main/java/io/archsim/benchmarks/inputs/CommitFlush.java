package io.archsim.benchmarks.inputs;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * {@code commitFlushMs}, the time of one WAL flush. With PostgreSQL's own statistics in
 * the top pass (since 31 Oct 2026), the mean time of its flushes there, shaped like the
 * commit wait the service measured. Without them, the flush with which the model's writes
 * wait as long for their lock and flush as the service's did, synchronous_commit on minus
 * off; on the trial of 31 Oct the two agreed within 5%.
 */
final class CommitFlush {

	/** The simulated seconds of PostgreSQL alone with a measured flush. */
	static final int FIT_SECONDS = 15;

	private static final double LEAST_WAIT_MS = 0.01;

	private static final double LEAST_SHAPE = 1.01;

	private static final int DECIMALS = 4;

	private static final List<String> MODEL_KEYS = List.of("meanLockWaitMs", "meanCommitWaitMs", "commitsPerFlush");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final DatabaseAlone database;

	CommitFlush(DatabaseAlone database) {
		this.database = database;
	}

	/**
	 * The flush input.
	 * @param experiment the experiment
	 * @param values the database's other inputs' values
	 * @param passes the top pass, the pass with synchronous_commit on, and the writes'
	 * round trips with it on and off
	 * @param readRatio the share of reads in the pass's mix
	 * @return the input: its value, how it was found, the model's waits and PostgreSQL's
	 * own figures
	 */
	ObjectNode of(Experiment experiment, ObjectNode values, Passes passes, double readRatio) {
		double median = Math.max(LEAST_WAIT_MS,
				passes.sync().get("median").doubleValue() - passes.async().get("median").doubleValue());
		double shape = Math.max(LEAST_SHAPE,
				(passes.sync().get("p95").doubleValue() - passes.async().get("p95").doubleValue()) / median);
		double waited = ModelDistributions.mean(ModelDistributions.lognormal(median, median * shape));
		return PostgresCpu.flushes(passes.high())
			.map((measured) -> measured(experiment, values, new Flushed(passes, readRatio, shape, waited), measured))
			.orElseGet(() -> fitted(experiment, values, new Flushed(passes, readRatio, shape, waited)));
	}

	private ObjectNode measured(Experiment experiment, ObjectNode values, Flushed flushed,
			PostgresCpu.WalFlushes measured) {
		ObjectNode flush = ModelDistributions.lognormalWithMean(measured.flushMs(),
				NODES.objectNode().put("median", 1.0).put("p95", flushed.shape()));
		JsonNode rate = flushed.passes().high().get("rateRps");
		JsonNode db = this.database.simulate(experiment, with(values, flush), rate,
				new DatabaseAlone.Mix(flushed.readRatio(), FIT_SECONDS));
		String how = "PostgreSQL's own WAL flushes at " + PythonFormat.format(rate.doubleValue(), "g")
				+ " req/s (pg_stat_wal): " + PythonFormat.format(measured.syncs().doubleValue(), ".0f") + " flushes of "
				+ PythonFormat.format(measured.flushMs(), ".3f") + " ms on average, "
				+ PythonFormat.format(measured.commitsPerFlush(), ".2f") + " commits per flush (the model: "
				+ PythonFormat.format(PythonValues.numberOrZero(db, "commitsPerFlush"), ".2f")
				+ "); shaped like the commit wait the service measured (p95/median "
				+ PythonFormat.format(flushed.shape(), ".2f") + ", mean " + PythonFormat.format(flushed.waited(), ".3f")
				+ " ms)";
		ObjectNode input = input(flush, how, db);
		input.putObject("postgres")
			.set("syncs", PythonValues.round(measured.syncs(), DECIMALS))
			.put("flushMs", PythonRound.round(measured.flushMs(), DECIMALS))
			.put("syncMs", PythonRound.round(measured.syncMs(), DECIMALS))
			.put("commitsPerFlush", PythonRound.round(measured.commitsPerFlush(), DECIMALS));
		return input;
	}

	private ObjectNode fitted(Experiment experiment, ObjectNode values, Flushed flushed) {
		JsonNode rate = flushed.passes().syncPass().get("rateRps");
		FlushFit.Result fit = new FlushFit(this.database).fit(experiment, values,
				new FlushFit.Target(rate, flushed.readRatio(), flushed.waited(), flushed.shape()));
		ObjectNode flush = ModelDistributions.lognormal(fit.medianMs(), fit.medianMs() * flushed.shape());
		JsonNode db = fit.database();
		String how = "fitted so that the model's writes wait as long for their lock and flush as the service's did at "
				+ PythonFormat.format(rate.doubleValue(), "g") + " req/s (synchronous_commit on minus off: median "
				+ PythonFormat.format(flushed.passes().sync().get("median").doubleValue(), ".3f") + " - "
				+ PythonFormat.format(flushed.passes().async().get("median").doubleValue(), ".3f") + " ms, p95/median "
				+ PythonFormat.format(flushed.shape(), ".2f") + ", a mean of "
				+ PythonFormat.format(flushed.waited(), ".3f") + " ms); in the model there: lock "
				+ PythonFormat.format(PythonValues.numberOrZero(db, "meanLockWaitMs"), ".3f") + " ms, flush "
				+ PythonFormat.format(PythonValues.numberOrZero(db, "meanCommitWaitMs"), ".3f") + " ms, "
				+ PythonFormat.format(PythonValues.numberOrZero(db, "commitsPerFlush"), ".2f") + " commits per flush";
		return input(flush, how, db);
	}

	private static ObjectNode input(ObjectNode flush, String how, JsonNode db) {
		ObjectNode input = NODES.objectNode().set("value", flush).put("how", how);
		ObjectNode model = input.putObject("model");
		MODEL_KEYS.forEach((key) -> model.set(key, PythonValues.get(db, key)));
		return input;
	}

	static ObjectNode with(ObjectNode values, JsonNode flush) {
		ObjectNode copy = values.deepCopy();
		copy.set("commitFlushMs", flush);
		return copy;
	}

	/**
	 * The passes the flush comes from.
	 *
	 * @param high the top pass, with PostgreSQL's own statistics when it read them
	 * @param syncPass the pass with synchronous_commit on, whose rate a fit simulates
	 * @param sync the writes' round trip with synchronous_commit on
	 * @param async the writes' round trip with it off
	 */
	record Passes(JsonNode high, JsonNode syncPass, JsonNode sync, JsonNode async) {

	}

	/**
	 * What the service measured of the flush.
	 *
	 * @param passes the passes
	 * @param readRatio the share of reads in the pass's mix
	 * @param shape the commit wait's p95 over its median
	 * @param waited the commit wait's mean (ms)
	 */
	private record Flushed(Passes passes, double readRatio, double shape, double waited) {

	}

}
