package io.archsim.benchmarks.calibration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.Traffic;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.engine.numeric.PythonRound;

/**
 * A stack settled after its data load, before anything is measured on it: VACUUM ANALYZE
 * and a checkpoint (so that autovacuum and the first checkpoint land in no pass), then a
 * discarded warm-up at the calibration's first rate (caches, connection pools, JIT).
 */
public final class Settling {

	/** The warm-up's seconds. */
	static final int SETTLE_S = 30;

	private Settling() {
	}

	/**
	 * Settles a stack.
	 * @param stack what drives and reads it
	 * @param k6 k6 on it
	 * @param log the log
	 * @param experiment the experiment whose traffic warms it up
	 * @return when, how long and its steps
	 */
	public static JsonNode settle(Instruments stack, K6 k6, HarnessLog log, Experiment experiment) {
		log.line("settling " + experiment.id() + ": VACUUM ANALYZE, CHECKPOINT, " + SETTLE_S + " s warm-up");
		double start = stack.ticker().now();
		stack.compose()
			.output("exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-q", "-c", "VACUUM ANALYZE", "-c",
					"CHECKPOINT");
		k6.run(Traffic.of(experiment, experiment.calibration().getFirst(), Calibration.FIRST_RPS),
				new K6.Window(0, SETTLE_S), "settle");
		ObjectNode settled = JsonNodeFactory.instance.objectNode().put("at", Timestamps.now(log.clock()));
		settled.put("seconds", PythonRound.round(stack.ticker().now() - start, 1));
		settled.putArray("steps")
			.add("VACUUM ANALYZE")
			.add("CHECKPOINT")
			.add(SETTLE_S + " s warm-up at " + Calibration.FIRST_RPS + " req/s");
		return settled;
	}

}
