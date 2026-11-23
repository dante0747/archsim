package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.comparison.Comparison;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * One kept run checked with today's model: its inputs derived again from its calibration
 * passes and anchored again, its measured ladder and its knee simulated, and the
 * comparison with what it measured. It is designed after the measurements were seen, so
 * it is never a prediction.
 */
public final class DevCheck {

	/** What a check is. */
	static final String KIND = "in-sample re-simulation";

	/** How a check was made, in its own words. */
	static final String HOW = "Inputs derived again from this run's calibration passes with today's harness, and its "
			+ "measured load steps simulated with today's engine. It is designed after the measurements were seen, so "
			+ "it checks a model change against data the change may have been tuned on: it is never a prediction and "
			+ "never shown as one.";

	private static final List<String> LATENCY_FIELDS = List.of("p50", "p95", "p99", "successRate", "throughputRps");

	private static final List<String> TWO_PASSES = List.of("low", "high");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Path results;

	private final Simulations simulations;

	private final ModelInputs inputs;

	private final CheckStamp stamp;

	/**
	 * Checks of the kept runs under a results folder.
	 * @param results the results folder
	 * @param inputs the inputs' derivation, and the engine's simulations it runs on
	 * @param stamp what names each check
	 */
	public DevCheck(Path results, ModelInputs inputs, CheckStamp stamp) {
		this.results = results;
		this.simulations = inputs.simulations();
		this.inputs = inputs;
		this.stamp = stamp;
	}

	/**
	 * A kept run's check.
	 * @param run the run
	 * @return the check; empty for a run without a calibration from a low and a top pass
	 * (run 1 had one pass) or without measurements
	 * @throws io.archsim.benchmarks.inputs.MissingReadingException when the calibration
	 * lacks a pass or a timing today's derivation needs
	 */
	public Optional<ObjectNode> of(KeptRun run) {
		JsonNode calibration = ResultFiles.read(run.file(KeptRun.CALIBRATION)).orElse(PythonValues.none());
		JsonNode measured = ResultFiles.read(run.file(KeptRun.MEASURED)).orElse(PythonValues.none());
		if (!PythonText.truthy(calibration) || !PythonText.truthy(measured) || !hasTwoPasses(calibration)) {
			return Optional.empty();
		}
		Experiment experiment = Experiments.standard().get(run.experiment());
		ObjectNode rederived = this.inputs.rederive(experiment,
				ModelInputs.withTrialGc(this.results, experiment, calibration));
		ArrayNode steps = NODES.arrayNode();
		measured.get("steps")
			.forEach((step) -> steps.add(this.simulations
				.simulate(Designs.design(experiment, rederived, step.get("offeredRps"), Designs.Window.STEP))));
		ObjectNode knee = this.simulations.knee(experiment, rederived, PythonValues.none(), Designs.Window.STEP);
		ObjectNode check = header(run, calibration);
		check.put("inputsAsCalibrated", PythonValues.equal(rederived, PythonValues.get(calibration, "inputs")));
		check.set("sameLatencyAsPredictions",
				sameLatency(steps, ResultFiles.read(run.file(KeptRun.PREDICTIONS)).orElse(NODES.objectNode())));
		check.set("inputs", rederived);
		check.set("knee", knee);
		check.set("steps", steps);
		ObjectNode simulated = NODES.objectNode().set("knee", knee).set("steps", steps);
		check.set("comparison", Comparison.compare(experiment, simulated, measured, PythonValues.none()));
		return Optional.of(check);
	}

	/**
	 * Where a check is written: {@code dev/<label>/<experiment>/<calibrated at>.json},
	 * named by the calibration, which stays with the run when a newer run moves it into
	 * {@code run-N/}.
	 * @param results the results folder
	 * @param label the engine's label
	 * @param experiment the experiment
	 * @param check the check
	 * @return the file
	 */
	public static Path path(Path results, String label, String experiment, JsonNode check) {
		JsonNode calibratedAt = PythonValues.get(check, "calibratedAt");
		String name = PythonText.truthy(calibratedAt) ? PythonText.str(calibratedAt)
				: check.get("results").stringValue().replace("/", "_");
		return results.resolve("dev").resolve(label).resolve(experiment).resolve(name.replace(':', '-') + ".json");
	}

	private ObjectNode header(KeptRun run, JsonNode calibration) {
		ObjectNode check = NODES.objectNode().put("experiment", run.experiment());
		check.put("results", run.folderUnder(this.results));
		check.set("calibratedAt", PythonValues.get(calibration, "at"));
		check.set("calibrationMethod", PythonValues.get(calibration, "method"));
		check.put("kind", KIND);
		check.put("designedAfterMeasurements", true);
		check.put("how", HOW);
		check.put("at", Timestamps.now(this.stamp.clock()));
		check.put("engineVersion", this.stamp.label());
		check.set("provenance", this.stamp.provenance().deepCopy());
		return check;
	}

	// how many simulated steps have the latency
	// the run's predictions recorded at their load
	private static ObjectNode sameLatency(ArrayNode steps, JsonNode predictions) {
		List<JsonNode> predicted = PythonValues.get(predictions, "steps").valueStream().toList();
		long same = steps.valueStream().filter((step) -> sameAsPredicted(step, predicted)).count();
		ObjectNode counts = NODES.objectNode().put("steps", same).put("of", steps.size());
		counts.set("predictedWith", PythonValues.get(predictions, "engineVersion"));
		return counts;
	}

	// the last predicted step at the same load, as a dict keyed by the load keeps it
	private static boolean sameAsPredicted(JsonNode step, List<JsonNode> predicted) {
		Optional<JsonNode> atLoad = predicted.reversed()
			.stream()
			.filter((other) -> PythonValues.equal(other.get("offeredRps"), step.get("offeredRps")))
			.findFirst();
		return atLoad.isPresent() && LATENCY_FIELDS.stream()
			.allMatch((field) -> PythonValues.equal(PythonValues.get(step, field),
					PythonValues.get(atLoad.get(), field)));
	}

	private static boolean hasTwoPasses(JsonNode calibration) {
		JsonNode passes = calibration.path("passes");
		return TWO_PASSES.stream().allMatch(passes::has);
	}

}
