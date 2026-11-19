package io.archsim.benchmarks.campaign;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.benchmarks.range.KneeRanges;
import io.archsim.benchmarks.range.Sensitivity;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.text.PythonFormat;

/**
 * An experiment's predictions from its calibration, before anything is measured: the knee
 * (the highest load with p95 at most 50 ms and at least 99% success), its range from the
 * committed fit ({@code knee-range.json}), and every step of the load ladder, from a
 * quarter of the knee to one and a half times it.
 */
public final class Prediction {

	/** The ladder's steps, as shares of the predicted knee. */
	static final List<Double> STEP_FRACTIONS = List.of(0.25, 0.5, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5);

	/** The ladder's least step. */
	static final long LEAST_STEP = 10;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Simulations simulations;

	private final Path rangeFit;

	private final Supplier<JsonNode> provenance;

	private final HarnessLog log;

	/**
	 * Predictions on the engine.
	 * @param simulations the engine's simulations
	 * @param rangeFit the knee's range fit, {@code benchmarks/knee-range.json}
	 * @param provenance what produces the predictions ({@code Provenance}), read as each
	 * experiment is predicted
	 * @param log the log, whose clock dates them
	 */
	public Prediction(Simulations simulations, Path rangeFit, Supplier<JsonNode> provenance, HarnessLog log) {
		this.simulations = simulations;
		this.rangeFit = rangeFit;
		this.provenance = provenance;
		this.log = log;
	}

	/**
	 * An experiment's predictions.
	 * @param experiment the experiment
	 * @param calibration its {@code calibration.json}
	 * @return its {@code predictions.json}
	 */
	public ObjectNode of(Experiment experiment, JsonNode calibration) {
		JsonNode inputs = calibration.get("inputs");
		this.log.line("predicting " + experiment.id() + ": searching the knee");
		ObjectNode knee = this.simulations.knee(experiment, inputs, PythonValues.none(), Designs.Window.STEP);
		JsonNode kneeRps = knee.get("kneeRps");
		Optional<ObjectNode> range = range(experiment, inputs, kneeRps);
		List<Long> steps = steps(kneeRps.doubleValue());
		this.log.line("predicted knee " + PythonFormat.format(kneeRps.doubleValue(), "g") + " req/s; steps "
				+ steps.toString());
		List<ObjectNode> simulated = InParallel.map(steps, (rps) -> this.simulations
			.simulate(Designs.design(experiment, inputs, PythonValues.integer(rps), Designs.Window.STEP)));
		ObjectNode predictions = NODES.objectNode().put("experiment", experiment.id());
		predictions.put("at", Timestamps.now(this.log.clock()));
		predictions.put("engineVersion", EngineVersions.ENGINE);
		predictions.put("reportVersion", EngineVersions.REPORT);
		predictions.set("provenance", this.provenance.get().deepCopy());
		predictions.putObject("objective").put("p95Ms", Objective.P95_MS).put("minSuccess", Objective.MIN_SUCCESS);
		predictions.set("knee", knee);
		range.ifPresent((kneeRange) -> predictions.set("kneeRange", kneeRange));
		ArrayNode ladder = predictions.putArray("steps");
		simulated.forEach(ladder::add);
		predictions.set("design", Designs.design(experiment, inputs, kneeRps, Designs.Window.STEP));
		return predictions;
	}

	/**
	 * The ladder's steps around a knee: each share of it, rounded, 10 req/s at least,
	 * each once, in order.
	 * @param knee the knee
	 * @return the steps
	 */
	static List<Long> steps(double knee) {
		TreeSet<Long> steps = new TreeSet<>();
		STEP_FRACTIONS.forEach((fraction) -> steps.add(Math.max(LEAST_STEP, (long) Math.rint(knee * fraction))));
		return new ArrayList<>(steps);
	}

	// the knee's range from the committed fit; none without a fit or a knee
	private Optional<ObjectNode> range(Experiment experiment, JsonNode inputs, JsonNode kneeRps) {
		Optional<JsonNode> fit = ResultFiles.read(this.rangeFit);
		if (fit.isEmpty()) {
			this.log.line("no " + this.rangeFit.getFileName() + ": predicting no range for the knee "
					+ "(./gradlew :archsim-benchmarks:run --args=\"range-fit\")");
			return Optional.empty();
		}
		if (!PythonValues.isNone(kneeRps) && kneeRps.doubleValue() != 0) {
			this.log.line("the knee's sensitivity to " + Sensitivity.inputsVaried(inputs) + " inputs, for its range");
			return KneeRanges.of(kneeRps, Sensitivity.of(this.simulations, experiment, inputs, kneeRps), fit.get());
		}
		return Optional.empty();
	}

}
