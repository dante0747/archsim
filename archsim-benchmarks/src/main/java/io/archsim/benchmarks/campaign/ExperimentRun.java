package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.comparison.Comparison;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * An experiment's run: calibrated, predicted, the predictions saved and sealed before the
 * first step is measured, each step measured, and the comparison written. A ladder still
 * meeting the objective at its top climbs on, each new step predicted, saved and sealed
 * before it is measured: {@code measured.json}'s {@code predictionsSha256} seals the
 * predictions the ladder began with, and each of its {@code extensionSeals} the
 * predictions as they were before that step, so every seal matches the bytes it sealed
 * (run.py saved the extensions into the sealed file after its one seal).
 */
public final class ExperimentRun {

	/** The most steps a ladder climbs above its predicted top. */
	static final int MAX_EXTENSIONS = 4;

	/** How far each extension climbs above the step before. */
	private static final double EXTENSION = 1.3;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final ExperimentSteps steps;

	private final Path results;

	private final Function<Experiment, Optional<String>> seal;

	private final HarnessLog log;

	/**
	 * Runs of experiments.
	 * @param steps what calibrates, predicts and measures
	 * @param results the results folder, an experiment's latest results in its folder
	 * @param seal why an experiment may not run now ({@link Seals}); empty when it may
	 * @param log the log, whose clock dates the files
	 */
	ExperimentRun(ExperimentSteps steps, Path results, Function<Experiment, Optional<String>> seal, HarnessLog log) {
		this.steps = steps;
		this.results = results;
		this.seal = seal;
		this.log = log;
	}

	/**
	 * Runs an experiment, once its seal allows it.
	 * @param experiment the experiment
	 * @throws HarnessStopException when it is sealed: a held-out experiment, on an engine
	 * not frozen or with uncommitted changes
	 */
	public void run(Experiment experiment) {
		Optional<String> sealed = this.seal.apply(experiment);
		if (sealed.isPresent()) {
			throw new HarnessStopException(sealed.get());
		}
		ObjectNode calibration = this.steps.calibrate(experiment);
		save(experiment, "calibration", calibration);
		ObjectNode predictions = this.steps.predict(experiment, calibration);
		save(experiment, "predictions", predictions);
		String digest = digest(experiment, "predictions");
		this.log.line(experiment.id() + ": predictions saved, sha256 " + digest);
		// these predictions carry CPU and memory: a re-simulation left by older results
		// is stale
		delete(folder(experiment).resolve("resources-predicted.json"));
		ObjectNode measured = NODES.objectNode().put("experiment", experiment.id());
		measured.put("at", Timestamps.now(this.log.clock()));
		measured.put("predictionsSha256", digest);
		ArrayNode measuredSteps = measured.putArray("steps");
		for (JsonNode step : predictions.get("steps")) {
			measuredSteps.add(this.steps.measure(experiment, step.get("offeredRps").longValue()));
			save(experiment, "measured", measured);
		}
		climb(experiment, new Run(calibration, predictions, measured));
		compare(experiment, predictions, measured);
	}

	// still meeting the objective at the ladder's top: the prediction was too
	// pessimistic; each step above it predicted, saved and sealed before it is measured
	private void climb(Experiment experiment, Run run) {
		ArrayNode measuredSteps = (ArrayNode) run.measured().get("steps");
		int extensions = 0;
		while (Objective.isMetBy(measuredSteps.get(measuredSteps.size() - 1)) && extensions < MAX_EXTENSIONS) {
			extensions++;
			long next = (long) Math
				.rint(measuredSteps.get(measuredSteps.size() - 1).get("offeredRps").doubleValue() * EXTENSION);
			this.log.line(experiment.id() + ": still meeting the objective; predicting and then measuring " + next
					+ " req/s");
			ObjectNode step = this.steps.predictStep(experiment, run.calibration().get("inputs"), next);
			step.put("extension", true);
			step.put("at", Timestamps.now(this.log.clock()));
			((ArrayNode) run.predictions().get("steps")).add(step);
			save(experiment, "predictions", run.predictions());
			String digest = digest(experiment, "predictions");
			this.log.line(experiment.id() + ": predictions saved with " + next + " req/s, sha256 " + digest);
			arrayOf(run.measured(), "extensionSeals").addObject()
				.put("offeredRps", next)
				.put("predictionsSha256", digest);
			save(experiment, "measured", run.measured());
			measuredSteps.add(this.steps.measure(experiment, next));
			save(experiment, "measured", run.measured());
		}
	}

	private void compare(Experiment experiment, ObjectNode predictions, ObjectNode measured) {
		ObjectNode comparison = Comparison.compare(experiment, predictions, measured, PythonValues.none());
		save(experiment, "comparison", comparison);
		StringBuilder events = new StringBuilder();
		this.steps.postgresLog()
			.lines()
			.filter((line) -> line.contains("checkpoint") || line.contains("automatic vacuum")
					|| line.contains("automatic analyze"))
			.forEach((line) -> events.append(line).append('\n'));
		write(folder(experiment).resolve("postgres-events.log"), events.toString());
		this.log.line(experiment.id() + ": knee measured "
				+ PythonText.str(PythonValues.get(comparison.get("measuredKnee"), "kneeRps")) + " vs predicted "
				+ PythonText.str(PythonValues.get(comparison.get("predictedKnee"), "kneeRps")) + " req/s (error "
				+ PythonText.str(comparison.get("kneeError")) + ")");
	}

	// writes one of the experiment's files
	private void save(Experiment experiment, String name, JsonNode data) {
		ResultFiles.write(folder(experiment).resolve(name + ".json"), data);
	}

	// the sha256 of one of the experiment's files, as written
	private String digest(Experiment experiment, String name) {
		return sha256(folder(experiment).resolve(name + ".json"));
	}

	private Path folder(Experiment experiment) {
		return this.results.resolve(experiment.id());
	}

	private static ArrayNode arrayOf(ObjectNode object, String key) {
		JsonNode array = object.get(key);
		return (array != null) ? (ArrayNode) array : object.putArray(key);
	}

	private static String sha256(Path file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A run's files as they grow.
	 *
	 * @param calibration its calibration
	 * @param predictions its predictions
	 * @param measured its measurements
	 */
	private record Run(ObjectNode calibration, ObjectNode predictions, ObjectNode measured) {

	}

}
