package io.archsim.benchmarks.inputs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * Every model input with the measured numbers and the formula it came from: the
 * service's, then the queue's (Kafka), the database's, the cache's and the services'
 * memory, anchored at the top pass. Each part's inputs come from a class of their own, so
 * a change to one part's calibration leaves the others alone.
 */
public final class ModelInputs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final DatabaseInputs database;

	private final Anchoring anchoring;

	/**
	 * Inputs whose fits and anchors simulate on an engine.
	 * @param simulations the simulations
	 */
	public ModelInputs(Simulations simulations) {
		this.database = new DatabaseInputs(new CommitFlush(new DatabaseAlone(simulations)));
		this.anchoring = new Anchoring((experiment, inputs, rps, window) -> simulations
			.simulate(Designs.design(experiment, inputs, rps, window)));
	}

	/**
	 * A run's inputs derived again from its calibration passes alone, and anchored again,
	 * as a calibration would with today's code: under protocol v5 one set per replicate
	 * (each top pass with its auxiliary mixes, the staircase's first pass, the idle pass,
	 * the whole staircase for the memory fit and the staircase's CPU by load), and their
	 * medians.
	 * @param experiment the experiment
	 * @param calibration the run's {@code calibration.json}
	 * @return the inputs
	 */
	public ObjectNode rederive(Experiment experiment, JsonNode calibration) {
		JsonNode passes = calibration.get("passes");
		List<ObjectNode> sets = new ArrayList<>();
		for (JsonNode replicate : PythonValues.get(calibration, "replicates").valueStream().toList()) {
			sets.add(replicateSet(replicate, passes, calibration));
		}
		if (sets.isEmpty()) {
			return anchored(experiment, passes);
		}
		return MedianInputs.of(sets.stream().map((set) -> anchored(experiment, set)).toList());
	}

	/**
	 * A calibration as a development check reads it: a Node.js service that did not
	 * report its garbage collection takes the GC per request its experiment's trial
	 * measured (W21), where there is one; a calibration from then on reads its own.
	 * @param results the results folder, whose {@code trials/} hold the trials
	 * @param experiment the run's experiment
	 * @param calibration the run's {@code calibration.json}
	 * @return the calibration, or a copy with the trial's GC
	 */
	public static JsonNode withTrialGc(Path results, Experiment experiment, JsonNode calibration) {
		if (!experiment.runsOnNode()) {
			return calibration;
		}
		return TrialGc.of(results, experiment).<JsonNode>map((gc) -> TrialGc.give(calibration, gc)).orElse(calibration);
	}

	/**
	 * The model inputs from the replicate sets: each input taken whole from the replicate
	 * whose value is the median ({@link MedianInputs}).
	 * @param replicates each replicate set's inputs
	 * @return the inputs
	 */
	public static ObjectNode median(List<ObjectNode> replicates) {
		return MedianInputs.of(replicates);
	}

	/**
	 * The inputs from one set of passes, before they are anchored: the service's, then
	 * the queue's (Kafka), the database's, the cache's and the services' memory.
	 * @param experiment the experiment
	 * @param passes the low, top and auxiliary passes, the idle pass and the staircase
	 * @return the inputs, each with its value and how it was found
	 */
	public ObjectNode of(Experiment experiment, JsonNode passes) {
		ObjectNode inputs = ServiceInputs.of(experiment, passes);
		if (experiment.stack().equals("kafka")) {
			inputs.setAll(QueueInputs.of(passes));
		}
		inputs.setAll(this.database.of(experiment, passes));
		if (experiment.hasCache()) {
			inputs.setAll(CacheInputs.of(experiment, passes));
		}
		inputs.setAll(MemoryInputs.of(experiment, passes));
		return inputs;
	}

	/**
	 * The inputs from one set of passes, anchored at its top pass.
	 * @param experiment the experiment
	 * @param passes the low, top and auxiliary passes, the idle pass, the staircase and
	 * its CPU by load
	 * @return the inputs
	 */
	public ObjectNode anchored(Experiment experiment, JsonNode passes) {
		ObjectNode inputs = of(experiment, passes);
		this.anchoring.anchor(experiment, inputs, passes.get("high"));
		return inputs;
	}

	// a replicate's passes with the staircase's first, the idle pass and the staircase
	private static ObjectNode replicateSet(JsonNode replicate, JsonNode passes, JsonNode calibration) {
		ObjectNode set = ((ObjectNode) replicate).deepCopy();
		set.set("low", passes.get("low"));
		set.set("idle", passes.has("idle") ? passes.get("idle") : PythonValues.get(calibration, "idle"));
		JsonNode staircase = PythonValues.get(calibration, "staircase");
		set.set("staircase", PythonText.truthy(staircase) ? staircase : NODES.arrayNode());
		set.set("cpuByLoad", PythonValues.get(calibration, "cpuByLoad"));
		return set;
	}

}
