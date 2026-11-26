package io.archsim.benchmarks.baselines;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * A kept run's baselines ({@code baselines.json}, ADR-0087): three simple estimates of
 * its knee from its calibration alone, to set beside the simulator's prediction. They
 * read the inputs the calibration recorded, the ones the run's predictions used, and no
 * measured load step. Their rules were fixed on {@link #RULES_FIXED}: for a run
 * calibrated before that day they were designed after its measurements were seen, and the
 * file says so; for a later run the rules predate the measurement. Either way they are
 * computed after the run and never sealed before it, as its predictions are.
 */
public final class Baselines {

	/**
	 * The day the estimates' rules were last changed (UTC): it moves with every change to
	 * a rule, which no test can check. A run calibrated on that day or before it counts
	 * as measured before the rules were written, since they may have changed later that
	 * day.
	 */
	public static final String RULES_FIXED = "2026-11-19";

	/** An estimate's knee, in requests per second, or None with why it has none. */
	public static final String KNEE = "kneeRps";

	/** The bottleneck arithmetic's estimate, in a file's {@code baselines}. */
	public static final String ARITHMETIC = "bottleneckArithmetic";

	/** The engine's analytic estimate, in a file's {@code baselines}. */
	public static final String ESTIMATE = "analyticEstimate";

	/** The utilisation extrapolation's estimate, in a file's {@code baselines}. */
	public static final String EXTRAPOLATION = "utilisationExtrapolation";

	/** The estimates of a file's {@code baselines}, in the order it holds them. */
	public static final List<String> ESTIMATES = List.of(ARITHMETIC, ESTIMATE, EXTRAPOLATION);

	/** What a baselines file is. */
	static final String KIND = "baselines";

	/** What the file holds, in its own words. */
	static final String HOW = "Simple estimates of the knee from this run's calibration alone, to set beside the "
			+ "simulator's prediction: no measured load step is used. Their rules were fixed on " + RULES_FIXED + ".";

	/** What the file adds for a run calibrated before the rules were fixed. */
	static final String RULES_AFTER_THE_RUN = " This run was calibrated and measured before that, so the rules were "
			+ "written with its results in view: its estimates check what the simulation adds and are not "
			+ "predictions.";

	/** What the file adds for a run calibrated once the rules were fixed. */
	static final String RULES_BEFORE_THE_RUN = " This run was calibrated after that day, so the rules predate its "
			+ "measurement; its estimates are still computed after the run, and are not sealed before it as its "
			+ "predictions are.";

	/** Which inputs the estimates read, in the file's words. */
	static final String INPUTS = "calibration.json's inputs as recorded, the ones predictions.json used, in the "
			+ "design today's harness builds from them";

	/** Where a file holds its estimates, each under its key. */
	private static final String FIGURES = "baselines";

	/** What names a file's making, which two files of the same figures may differ in. */
	private static final List<String> STAMP = List.of("at", "engineVersion", "provenance");

	/** The misses a design without a cache passes on to its database: it has none. */
	private static final double NO_CACHE = 0.0;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Estimates estimates;

	private final Cpusets declared;

	private final CheckStamp stamp;

	/**
	 * Baselines whose analytic estimate is an engine's.
	 * @param estimates the engine's instant estimates
	 * @param declared the cpusets {@code compose.yaml} declares, for a run that recorded
	 * none
	 * @param stamp the engine's version, what produced the files and the clock that dates
	 * them
	 */
	public Baselines(Estimates estimates, Cpusets declared, CheckStamp stamp) {
		this.estimates = estimates;
		this.declared = declared;
		this.stamp = stamp;
	}

	/**
	 * A kept run's baselines.
	 * @param run the run
	 * @return the file's value; empty for a run without a calibration
	 */
	public Optional<ObjectNode> of(KeptRun run) {
		JsonNode calibration = PythonValues.orNone(ResultFiles.read(run.file(KeptRun.CALIBRATION)));
		if (!PythonText.truthy(calibration)) {
			return Optional.empty();
		}
		Experiment experiment = Experiments.standard().get(run.experiment());
		JsonNode inputs = calibration.get("inputs");
		ContainerCpus cpus = ContainerCpus.of(PythonValues.orNone(ResultFiles.read(run.file(KeptRun.ENVIRONMENT))),
				this.declared, experiment.stack());
		ObjectNode file = header(run, calibration);
		ObjectNode baselines = file.putObject(FIGURES);
		baselines.set(ARITHMETIC, arithmetic(experiment, calibration));
		baselines.set(ESTIMATE, AnalyticEstimate
			.of((rps) -> this.estimates.estimate(Designs.design(experiment, inputs, rps, Designs.Window.STEP))));
		baselines.set(EXTRAPOLATION, UtilisationExtrapolation.of(calibration, cpus));
		return Optional.of(file);
	}

	/**
	 * An estimate in a run's baselines: its knee, how it was found and what bounds it.
	 * @param file the run's {@code baselines.json}, or None for a run without one
	 * @param estimate the estimate's key, one of {@link #ESTIMATES}
	 * @return the estimate, or None
	 */
	public static JsonNode estimate(JsonNode file, String estimate) {
		return file.path(FIGURES).path(estimate);
	}

	/**
	 * An estimate's knee in a run's baselines.
	 * @param file the run's {@code baselines.json}, or None for a run without one
	 * @param estimate the estimate's key, one of {@link #ESTIMATES}
	 * @return the knee in requests per second, or None
	 */
	public static JsonNode knee(JsonNode file, String estimate) {
		return estimate(file, estimate).path(KNEE);
	}

	/**
	 * Whether two baselines files hold the same figures: everything but when, by which
	 * engine version and by which build each was made.
	 * @param one a file's value
	 * @param other another's
	 * @return whether they differ in their stamp at most
	 */
	public static boolean sameFigures(JsonNode one, JsonNode other) {
		return PythonValues.equal(withoutStamp(one), withoutStamp(other));
	}

	// the design's parts are the same at every load: the arithmetic reads no load
	private static ObjectNode arithmetic(Experiment experiment, JsonNode calibration) {
		ObjectNode design = Designs.design(experiment, calibration.get("inputs"), PythonValues.integer(0),
				Designs.Window.STEP);
		if (!experiment.hasCache()) {
			return BottleneckArithmetic.of(design, NO_CACHE);
		}
		CacheMisses misses = CacheMisses.of(calibration);
		ObjectNode arithmetic = BottleneckArithmetic.of(design, misses.share());
		arithmetic.set("cacheMisses", misses.json());
		return arithmetic;
	}

	private ObjectNode header(KeptRun run, JsonNode calibration) {
		boolean rulesAfterTheRun = calibratedBeforeTheRules(calibration);
		ObjectNode file = NODES.objectNode().put("experiment", run.experiment());
		file.set("calibratedAt", PythonValues.get(calibration, "at"));
		file.put("kind", KIND);
		file.put("rulesFixed", RULES_FIXED);
		file.put("designedAfterMeasurements", rulesAfterTheRun);
		file.put("how", HOW + (rulesAfterTheRun ? RULES_AFTER_THE_RUN : RULES_BEFORE_THE_RUN));
		file.put("inputs", INPUTS);
		file.put("at", Timestamps.now(this.stamp.clock()));
		file.put("engineVersion", this.stamp.label());
		file.set("provenance", this.stamp.provenance().deepCopy());
		file.putObject("objective").put("p95Ms", Objective.P95_MS).put("minSuccess", Objective.MIN_SUCCESS);
		return file;
	}

	// a calibration that records no time cannot be shown to follow the rules, so its
	// estimates are not passed off as designed before its measurement
	private static boolean calibratedBeforeTheRules(JsonNode calibration) {
		JsonNode at = PythonValues.get(calibration, "at");
		return PythonValues.isNone(at) || day(at.stringValue()).compareTo(RULES_FIXED) <= 0;
	}

	// the day of a time as the results write it: 2026-11-19 of 2026-11-19T08:00:00Z
	private static String day(String time) {
		return time.substring(0, Math.min(time.length(), RULES_FIXED.length()));
	}

	private static JsonNode withoutStamp(JsonNode file) {
		if (!file.isObject()) {
			return file;
		}
		ObjectNode figures = ((ObjectNode) file).deepCopy();
		STAMP.forEach(figures::remove);
		return figures;
	}

}
