package io.archsim.benchmarks.range;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.resimulation.DevCheck;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonText;

/**
 * Fits the predicted range and checks its coverage: {@code range-fit}. The inputs and
 * knees of the counted kept runs come from this engine's development checks, or a label's
 * whose harness derived the inputs and whose engine simulated exactly as today's; the
 * missing ones are derived here. Each counted run's sensitivity is simulated (one knee
 * search per varied input, and one unperturbed). Finished work is kept in a cache outside
 * the repository.
 */
public final class RangeFit {

	private static final int SCALE_DECIMALS = 6;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Path results;

	private final Simulations simulations;

	private final CheckStamp stamp;

	private final HarnessLog log;

	/**
	 * A fit of the kept runs under a results folder.
	 * @param results the results folder
	 * @param simulations the engine's simulations
	 * @param stamp the engine's label, the provenance and the clock that dates the fit
	 * @param log the log
	 */
	public RangeFit(Path results, Simulations simulations, CheckStamp stamp, HarnessLog log) {
		this.results = results;
		this.simulations = simulations;
		this.stamp = stamp;
		this.log = log;
	}

	/**
	 * Fits the range and writes it.
	 * @param out the file to write ({@code benchmarks/knee-range.json})
	 * @param reuse the label of the development checks to take the inputs and knees from;
	 * {@code null} for this engine's
	 * @param cacheFile the file that keeps the finished work ({@link #cachePath})
	 * @return the fit, as written
	 */
	public ObjectNode fit(Path out, @Nullable String reuse, Path cacheFile) {
		String label = (reuse != null) ? reuse : this.stamp.label();
		RangeCache cache = RangeCache.in(cacheFile, this.log);
		Sources sources = Sources.of(this.results, label);
		this.log.line("range fit, engine " + this.stamp.label() + ": " + sources.all().size() + " calibrations, "
				+ sources.checked() + " with engine " + label + "'s development checks, "
				+ (sources.all().size() - sources.checked()) + " to derive (or in the cache)");
		Work work = new Work(this.results, this.simulations, sources, cache);
		cache.run(work.deriveCounted());
		cache.run(work.kneesOfCounted());
		List<Pending> pending = work.pending();
		List<RangeCache.Task> tasks = work.deriveTheRest();
		tasks.addAll(work.sensitivities(pending));
		long searches = pending.stream().mapToLong((run) -> Sensitivity.jobs(run.inputs()).size()).sum();
		this.log.line("range fit: " + pending.size() + " counted runs; " + searches + " knee searches for their "
				+ "sensitivity (" + tasks.stream().filter((task) -> cache.has(task.key())).count() + " of "
				+ tasks.size() + " tasks already done)");
		cache.run(tasks);
		ObjectNode fit = document(new Fitted(work.calibrations(), work.counted(pending)), this.stamp, label);
		ResultFiles.write(out, fit);
		return fit;
	}

	/**
	 * Where a fit keeps the work it has finished: outside the repository, in the system's
	 * temporary folder, by engine.
	 * @param engine the engine's label
	 * @return the cache's file
	 */
	public static Path cachePath(String engine) {
		return Path.of(System.getProperty("java.io.tmpdir"), "archsim-range-fit-" + engine + ".json");
	}

	/**
	 * The fit's file: its variation and model error, the check of its coverage, the
	 * calibrations and the runs it was fitted on.
	 * @param fitted the calibrations and the counted runs with an error
	 * @param stamp the engine's label, the provenance and the clock
	 * @param label the label the inputs and knees came from
	 * @return the file's content
	 */
	static ObjectNode document(Fitted fitted, CheckStamp stamp, String label) {
		ObjectNode fit = RangeCheck.foldFit(fitted.calibrations(), fitted.runs());
		RangeCheck.Result check = RangeCheck.check(fitted.calibrations(), fitted.runs());
		ObjectNode document = FitDocument.header(stamp, label, fitted.runs());
		document.setAll(fit);
		document.set("check", FitDocument.check(check, fitted.runs()));
		document.putArray("calibrations").addAll(fitted.calibrations());
		document.set("checked", FitDocument.checked(fitted.runs(), fit, check));
		return document;
	}

	/**
	 * What a fit is fitted on.
	 *
	 * @param calibrations one row per calibration: its experiment, source, calibration's
	 * time and each varied input's scale
	 * @param runs the counted runs with a measured knee
	 */
	record Fitted(List<JsonNode> calibrations, List<CountedRun> runs) {

		Fitted {
			calibrations = List.copyOf(calibrations);
			runs = List.copyOf(runs);
		}

	}

	/**
	 * A counted run before its sensitivity is known.
	 *
	 * @param source the run's calibration
	 * @param comparison its {@code comparison.json}
	 * @param inputs its inputs today
	 * @param knee its knee today
	 */
	record Pending(RangeSources.Source source, JsonNode comparison, JsonNode inputs, JsonNode knee) {

	}

	/**
	 * The calibrations and the development checks that give the inputs and knees of some.
	 *
	 * @param all the calibrations
	 * @param checks each one's check, None for a trial or one the label has none of
	 * @param checked how many have a check
	 */
	record Sources(List<RangeSources.Source> all, List<JsonNode> checks, long checked) {

		static Sources of(Path results, String label) {
			List<RangeSources.Source> all = RangeSources.of(results);
			List<JsonNode> checks = new ArrayList<>();
			for (RangeSources.Source source : all) {
				ObjectNode named = NODES.objectNode().put("results", source.source());
				named.set("calibratedAt", source.calibratedAt());
				JsonNode check = source.kind().equals("kept")
						? ResultFiles.read(DevCheck.path(results, label, source.experiment(), named))
							.orElse(PythonValues.none())
						: PythonValues.none();
				boolean usable = check.isObject() && label.equals(check.path("engineVersion").stringValue(""));
				checks.add(usable ? check : PythonValues.none());
			}
			return new Sources(all, checks, checks.stream().filter(JsonNode::isObject).count());
		}

	}

	/**
	 * The fit's work: today's inputs and knees where no check gives them, then each
	 * counted run's sensitivity, all through the cache.
	 */
	private static final class Work {

		private final Path results;

		private final Simulations simulations;

		private final ModelInputs modelInputs;

		private final Sources sources;

		private final RangeCache cache;

		private final List<Integer> counted = new ArrayList<>();

		private final List<JsonNode> comparisons = new ArrayList<>();

		Work(Path results, Simulations simulations, Sources sources, RangeCache cache) {
			this.results = results;
			this.simulations = simulations;
			this.modelInputs = new ModelInputs(simulations);
			this.sources = sources;
			this.cache = cache;
			for (int index = 0; index < sources.all().size(); index++) {
				RangeSources.Source source = sources.all().get(index);
				JsonNode comparison = ResultFiles.read(source.directory().resolve("comparison.json"))
					.orElse(PythonValues.none());
				if (source.kind().equals("kept") && PythonText.truthy(comparison)
						&& Knees.disturbedSteps(comparison.get("rows").valueStream().toList()).isEmpty()) {
					this.counted.add(index);
					this.comparisons.add(comparison);
				}
			}
		}

		// the counted runs need their inputs and knee before their sensitivity
		List<RangeCache.Task> deriveCounted() {
			return derive(this.counted.stream().filter((index) -> !hasCheck(index)).toList());
		}

		List<RangeCache.Task> kneesOfCounted() {
			List<RangeCache.Task> tasks = new ArrayList<>();
			for (int index : this.counted) {
				if (!hasCheck(index)) {
					RangeSources.Source source = this.sources.all().get(index);
					JsonNode inputs = inputs(index);
					tasks.add(new RangeCache.Task("knee|" + source.source(), () -> this.simulations
						.knee(experiment(source), inputs, PythonValues.none(), Designs.Window.STEP)));
				}
			}
			return tasks;
		}

		List<Pending> pending() {
			List<Pending> pending = new ArrayList<>();
			for (int place = 0; place < this.counted.size(); place++) {
				int index = this.counted.get(place);
				RangeSources.Source source = this.sources.all().get(index);
				JsonNode knee = hasCheck(index) ? this.sources.checks().get(index).get("knee")
						: this.cache.get("knee|" + source.source()).orElseThrow();
				pending.add(new Pending(source, this.comparisons.get(place), inputs(index), knee.get("kneeRps")));
			}
			return pending;
		}

		// the other calibrations' inputs
		List<RangeCache.Task> deriveTheRest() {
			List<Integer> rest = new ArrayList<>();
			for (int index = 0; index < this.sources.all().size(); index++) {
				if (!hasCheck(index) && !this.cache.has(inputsKey(index))) {
					rest.add(index);
				}
			}
			return new ArrayList<>(derive(rest));
		}

		List<RangeCache.Task> sensitivities(List<Pending> pending) {
			List<RangeCache.Task> tasks = new ArrayList<>();
			for (Pending run : pending) {
				List<JsonNode> jobs = Sensitivity.jobs(run.inputs());
				for (int job = 0; job < jobs.size(); job++) {
					JsonNode inputs = jobs.get(job);
					tasks.add(new RangeCache.Task("sens|" + run.source().source() + "|" + job, () -> PythonValues
						.number(Sensitivity.kneeNear(this.simulations, experiment(run.source()), inputs, run.knee()))));
				}
			}
			return tasks;
		}

		// one row per calibration: each varied input's scale, to 6 decimals
		List<JsonNode> calibrations() {
			List<JsonNode> rows = new ArrayList<>();
			for (int index = 0; index < this.sources.all().size(); index++) {
				RangeSources.Source source = this.sources.all().get(index);
				JsonNode inputs = inputs(index);
				ObjectNode row = NODES.objectNode()
					.put("experiment", source.experiment())
					.put("source", source.source());
				row.set("calibratedAt", source.calibratedAt());
				ObjectNode scales = row.putObject("scales");
				RangeInputs.keys(inputs)
					.forEach((key) -> scales.put(key, PythonRound.round(
							Objects.requireNonNull(RangeInputs.scale(inputs.get(key).get("value"))), SCALE_DECIMALS)));
				rows.add(row);
			}
			return rows;
		}

		List<CountedRun> counted(List<Pending> pending) {
			List<CountedRun> runs = new ArrayList<>();
			for (Pending run : pending) {
				List<JsonNode> jobs = Sensitivity.jobs(run.inputs());
				List<Double> knees = new ArrayList<>();
				for (int job = 0; job < jobs.size(); job++) {
					knees.add(this.cache.get("sens|" + run.source().source() + "|" + job).orElseThrow().doubleValue());
				}
				JsonNode measured = PythonValues.get(run.comparison().get("measuredKnee"), "kneeRps");
				if (PythonText.truthy(measured) && PythonText.truthy(run.knee())) {
					runs.add(new CountedRun(run.source().experiment(), run.source().source(),
							run.source().calibratedAt(), run.comparison().get("measuredKnee"), run.knee(),
							Sensitivity.fromKnees(run.inputs(), knees),
							StrictMath.log(measured.doubleValue() / run.knee().doubleValue()),
							PythonValues.get(run.comparison(), "kneeRange")));
				}
			}
			return runs;
		}

		private List<RangeCache.Task> derive(List<Integer> indexes) {
			return indexes.stream().map((index) -> {
				RangeSources.Source source = this.sources.all().get(index);
				Experiment experiment = experiment(source);
				return new RangeCache.Task(inputsKey(index), () -> this.modelInputs.rederive(experiment,
						ModelInputs.withTrialGc(this.results, experiment, source.calibration())));
			}).toList();
		}

		private boolean hasCheck(int index) {
			return this.sources.checks().get(index).isObject();
		}

		private JsonNode inputs(int index) {
			return hasCheck(index) ? this.sources.checks().get(index).get("inputs")
					: this.cache.get(inputsKey(index)).orElse(PythonValues.none());
		}

		private String inputsKey(int index) {
			return "inputs|" + this.sources.all().get(index).source();
		}

		private static Experiment experiment(RangeSources.Source source) {
			return Experiments.standard().get(source.experiment());
		}

	}

}
