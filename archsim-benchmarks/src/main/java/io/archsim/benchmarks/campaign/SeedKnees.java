package io.archsim.benchmarks.campaign;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.benchmarks.values.PythonValues;

/**
 * A predicted knee searched again at other seeds. The knee search judges one simulation
 * of each load, so the knee it finds depends on what its seed drew; searching again from
 * that knee at other seeds says by how much. The prediction itself stays the designs' own
 * seed's, as every kept prediction, every development check and the range's fit are, so
 * that a new prediction stays comparable with them.
 */
final class SeedKnees {

	/** The seeds a prediction searches its knee at again. */
	static final List<Integer> MORE_SEEDS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String KNEE = "kneeRps";

	private final Simulations simulations;

	private final List<Integer> seeds;

	/**
	 * Knees at other seeds.
	 * @param simulations the engine's simulations
	 * @param seeds the seeds to search at, besides the designs' own
	 */
	SeedKnees(Simulations simulations, List<Integer> seeds) {
		this.simulations = simulations;
		this.seeds = List.copyOf(seeds);
	}

	/**
	 * The knee at each seed, the searches side by side.
	 * @param experiment the experiment
	 * @param inputs its model inputs
	 * @param kneeRps the knee found at the designs' own seed
	 * @return {@code knees}, each seed's (the designs' own first, the others with the
	 * loads their search tried), and {@code lowRps} and {@code highRps}, the least and
	 * the greatest of them
	 */
	ObjectNode of(Experiment experiment, JsonNode inputs, JsonNode kneeRps) {
		ArrayNode knees = NODES.arrayNode();
		knees.addObject().put("seed", Designs.SEED).set(KNEE, kneeRps);
		InParallel.map(this.seeds, (seed) -> searched(experiment, inputs, kneeRps, seed)).forEach(knees::add);
		List<JsonNode> rates = knees.valueStream().map((knee) -> knee.get(KNEE)).toList();
		ObjectNode bySeed = NODES.objectNode();
		bySeed.set("lowRps", PythonValues.min(rates));
		bySeed.set("highRps", PythonValues.max(rates));
		bySeed.set("knees", knees);
		return bySeed;
	}

	private ObjectNode searched(Experiment experiment, JsonNode inputs, JsonNode near, int seed) {
		ObjectNode knee = NODES.objectNode().put("seed", seed);
		knee.setAll(this.simulations.kneeAtSeed(experiment, inputs, near, seed));
		return knee;
	}

}
