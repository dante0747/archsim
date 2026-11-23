package io.archsim.benchmarks.inputs;

import java.nio.file.Path;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.CalibratedRuns;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonJson;

/**
 * A kept run and the inputs the Python harness derived again from its calibration, with
 * the Java engine: its development check in {@code results/dev/0.16.0-java/}.
 *
 * @param experiment the run's experiment
 * @param calibration its {@code calibration.json}
 * @param check its development check
 */
record RecordedRun(Experiment experiment, JsonNode calibration, JsonNode check) {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final CalibratedRuns RUNS = CalibratedRuns.of(LAYOUT.results());

	/** What the median of the replicates puts before how an input was found. */
	private static final Pattern MEDIAN_PREFIX = Pattern.compile("^the median of [^:]+\\): ");

	/**
	 * A run, by its development check.
	 * @param experiment the experiment's ID
	 * @param calibratedAt the check's name: when the run was calibrated
	 * @return the run
	 */
	static RecordedRun of(String experiment, String calibratedAt) {
		Path dev = LAYOUT.results().resolve("dev/0.16.0-java").resolve(experiment).resolve(calibratedAt + ".json");
		JsonNode check = ResultFiles.read(dev).orElseThrow();
		return new RecordedRun(Experiments.standard().get(experiment), calibrationOf(experiment, check), check);
	}

	// found by its time: a run that was the latest when it was checked has moved to
	// run-N/
	private static JsonNode calibrationOf(String experiment, JsonNode check) {
		String calibratedAt = check.get("calibratedAt").stringValue();
		KeptRun run = RUNS.find(experiment, calibratedAt).orElseThrow();
		return ResultFiles.read(run.file(KeptRun.CALIBRATION)).orElseThrow();
	}

	/**
	 * The calibration as the development check read it: a Node.js service that did not
	 * report its garbage collection has its trial's.
	 * @return the calibration
	 */
	JsonNode calibrationAsChecked() {
		return ModelInputs.withTrialGc(LAYOUT.results(), this.experiment, this.calibration);
	}

	/**
	 * The calibration's passes.
	 * @return the passes
	 */
	JsonNode passes() {
		return this.calibration.get("passes");
	}

	/**
	 * The first replicate's passes, as {@code rederive_inputs} builds each replicate's:
	 * its top pass and auxiliary mixes, the staircase's first pass, the idle pass, the
	 * whole staircase and its CPU by load.
	 * @return the passes
	 */
	ObjectNode firstReplicate() {
		ObjectNode passes = ((ObjectNode) this.calibration.get("replicates").get(0)).deepCopy();
		passes.set("low", passes().get("low"));
		passes.set("idle", passes().has("idle") ? passes().get("idle") : this.calibration.get("idle"));
		passes.set("staircase", this.calibration.get("staircase"));
		passes.set("cpuByLoad", this.calibration.get("cpuByLoad"));
		return passes;
	}

	/**
	 * Some of the recorded inputs, in the order the harness wrote them.
	 * @param keys the inputs' names
	 * @return the inputs
	 */
	ObjectNode inputs(String... keys) {
		ObjectNode some = JsonNodeFactory.instance.objectNode();
		this.check.get("inputs").properties().forEach((input) -> {
			for (String key : keys) {
				if (key.equals(input.getKey())) {
					some.set(key, input.getValue());
				}
			}
		});
		return some;
	}

	/**
	 * Recorded inputs as one replicate gave them: without the prefix the median of the
	 * replicates puts before how each was found.
	 * @param inputs the recorded inputs
	 * @return a copy, each {@code how} without its prefix
	 */
	static ObjectNode ownHows(ObjectNode inputs) {
		ObjectNode own = inputs.deepCopy();
		own.properties().forEach((input) -> {
			if (input.getValue() instanceof ObjectNode value && value.has("how")) {
				value.put("how", MEDIAN_PREFIX.matcher(value.get("how").stringValue()).replaceFirst(""));
			}
		});
		return own;
	}

	/**
	 * Inputs as the harness writes them: indented JSON, keys in order.
	 * @param inputs the inputs
	 * @return their text
	 */
	static String text(JsonNode inputs) {
		return PythonJson.writeIndented(inputs, 2);
	}

}
