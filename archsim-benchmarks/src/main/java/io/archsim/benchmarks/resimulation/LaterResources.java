package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.text.PythonText;

/**
 * The CPU and memory of predictions that recorded none: {@code resources}. Each
 * experiment's latest results whose predictions have no resources get
 * {@code resources-predicted.json}, every predicted step simulated again with today's
 * engine from the calibration's inputs only, the inputs the predictions used; no measured
 * step is used.
 */
public final class LaterResources {

	/** How the file's resources were found, in its own words. */
	static final String HOW = "Simulated again after the measurements, from calibration.json's inputs only (the inputs "
			+ "predictions.json used; no measured step is used), because the predictions of these results recorded no "
			+ "CPU or memory. The latency predictions are predictions.json's, unchanged.";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Simulations simulations;

	private final Clock clock;

	private final HarnessLog log;

	/**
	 * Resources simulated on an engine.
	 * @param simulations the engine's simulations
	 * @param clock the clock that dates each file
	 * @param log the log, a line per experiment simulated
	 */
	public LaterResources(Simulations simulations, Clock clock, HarnessLog log) {
		this.simulations = simulations;
		this.clock = clock;
		this.log = log;
	}

	/**
	 * Writes the resources of every experiment's latest predictions that have none.
	 * @param results the results folder
	 * @return the files written, in the experiments' order
	 */
	public List<Path> predict(Path results) {
		List<Path> written = new ArrayList<>();
		for (Experiment experiment : Experiments.standard().all()) {
			KeptRun latest = new KeptRun(experiment.id(), results.resolve(experiment.id()));
			JsonNode predictions = ResultFiles.read(latest.file(KeptRun.PREDICTIONS)).orElse(PythonValues.none());
			JsonNode calibration = ResultFiles.read(latest.file(KeptRun.CALIBRATION)).orElse(PythonValues.none());
			if (PythonText.truthy(predictions) && PythonText.truthy(calibration) && lacksResources(predictions)) {
				ResultFiles.write(latest.file(KeptRun.LATER_RESOURCES),
						resources(experiment, predictions, calibration.get("inputs")));
				written.add(latest.file(KeptRun.LATER_RESOURCES));
			}
		}
		return written;
	}

	private ObjectNode resources(Experiment experiment, JsonNode predictions, JsonNode inputs) {
		List<JsonNode> loads = predictions.get("steps").valueStream().map((step) -> step.get("offeredRps")).toList();
		this.log.line(experiment.id() + ": simulating " + loads.size() + " steps again for their CPU and memory");
		List<ObjectNode> simulated = InParallel.map(loads,
				(rps) -> this.simulations.simulate(Designs.design(experiment, inputs, rps, Designs.Window.STEP)));
		ObjectNode file = NODES.objectNode().put("experiment", experiment.id());
		file.put("at", Timestamps.now(this.clock));
		file.put("engineVersion", EngineVersions.ENGINE);
		file.put("how", HOW);
		ArrayNode steps = file.putArray("steps");
		for (int index = 0; index < loads.size(); index++) {
			steps.addObject()
				.set("offeredRps", loads.get(index))
				.set("resources", simulated.get(index).get("resources"));
		}
		return file;
	}

	private static boolean lacksResources(JsonNode predictions) {
		return predictions.get("steps").valueStream().noneMatch((step) -> step.has("resources"));
	}

}
