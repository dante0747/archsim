package io.archsim.benchmarks.report;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;

/**
 * One experiment's latest run as {@code accuracy.json} holds it: the summary, low-load
 * and tier tables' numbers, how far its measured knee moved between kept runs, its CPU
 * and memory, and its machine.
 */
final class AccuracyExperiment {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private AccuracyExperiment() {
	}

	/**
	 * An experiment's latest run.
	 * @param experiment the experiment
	 * @param runs its kept runs ({@link RunHistory#of})
	 * @param sources the results and what labels them
	 * @return the experiment; empty without a calibration, predictions and a comparison
	 */
	static Optional<ObjectNode> of(Experiment experiment, List<JsonNode> runs, ReportSources sources) {
		Optional<JsonNode> calibration = LatestRuns.load(sources.results(), experiment.id(), KeptRun.CALIBRATION);
		Optional<JsonNode> predictions = LatestRuns.load(sources.results(), experiment.id(), KeptRun.PREDICTIONS);
		Optional<JsonNode> comparison = LatestRuns.load(sources.results(), experiment.id(), KeptRun.COMPARISON);
		if (calibration.isEmpty() || predictions.isEmpty() || comparison.isEmpty()) {
			return Optional.empty();
		}
		Latest latest = new Latest(calibration.get(), predictions.get(), comparison.get());
		ObjectNode json = head(experiment, runs, latest, sources);
		Body.add(json, comparison.get(), Tiers.criteria(comparison.get(), runs), runs);
		JsonNode environment = LatestRuns.load(sources.results(), experiment.id(), KeptRun.ENVIRONMENT)
			.orElse(NODES.objectNode());
		json.set("environment", environment(experiment, environment, sources.cpusets()));
		return Optional.of(json);
	}

	// the experiment and its latest run: when, by which engine, under which protocol
	private static ObjectNode head(Experiment experiment, List<JsonNode> runs, Latest latest, ReportSources sources) {
		String protocol = RunHistory.protocolOf(latest.calibration());
		String calibratedAt = latest.calibration().get("at").stringValue();
		JsonNode engine = PythonValues.get(latest.predictions(), "engineVersion");
		ObjectNode json = NODES.objectNode().put("id", experiment.id());
		json.put("name", PageTexts.standard().name(experiment.id()));
		json.set("title", latest.comparison().get("title"));
		ArrayNode technologies = json.putArray("technologies");
		PageTexts.standard().technologies(experiment.id()).forEach(technologies::add);
		json.set("objective", latest.comparison().get("objective"));
		json.put("date", Figures.date(calibratedAt));
		json.put("calibratedAt", calibratedAt);
		json.set("predictedAt", latest.predictions().get("at"));
		json.set("engineVersion", engine);
		json.put("sample", sources.engines().sampleLabel(engine, experiment));
		json.put("protocol", protocol);
		json.put("runs", runs.size());
		json.put("inSample", runs.stream().anyMatch((run) -> !run.get("protocol").stringValue().equals(protocol)));
		json.set("caveat",
				PageTexts.standard()
					.harnessCaveat(experiment.id(), latest.calibration().get("at"))
					.<JsonNode>map(NODES::stringNode)
					.orElse(PythonValues.none()));
		return json;
	}

	/**
	 * An experiment's machine, its Docker and the CPUs each container was pinned to.
	 * @param experiment the experiment
	 * @param environment its latest run's {@code environment.json}
	 * @param cpusets the runner's default cpusets
	 * @return the environment
	 */
	static ObjectNode environment(Experiment experiment, JsonNode environment, Cpusets cpusets) {
		JsonNode recorded = PythonValues.get(environment, "cpusets");
		Map<String, String> sets = EnvironmentBlock.stackCpusets(experiment.stack(), recorded, cpusets);
		JsonNode cpus = PythonValues.get(environment, "dockerCpus");
		ObjectNode json = NODES.objectNode();
		List.of("cpu", "os", "powerMode", "docker").forEach((key) -> json.set(key, PythonValues.get(environment, key)));
		json.set("dockerCpus", (cpus.isString() && isDigits(cpus.stringValue()))
				? PythonValues.integer(Long.parseLong(cpus.stringValue())) : cpus);
		json.put("dockerMemoryGiB", PythonRound.round(EnvironmentBlock.dockerMemoryGib(environment), 1));
		JsonNode images = PythonValues.get(environment, "images");
		json.set("images", images.isObject() ? images : NODES.objectNode());
		json.set("python", PythonValues.get(environment, "python"));
		ArrayNode pinned = json.putArray("cpusets");
		sets.forEach((name, set) -> pinned.addObject()
			.put("container", PageTexts.standard().container(name))
			.put("cpus", set)
			.put("count", Cpusets.count(set)));
		json.put("cpusetsRecorded", !PythonValues.isNone(recorded));
		json.put("text", EnvironmentBlock.machineText(environment));
		return json;
	}

	private static boolean isDigits(String text) {
		return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
	}

	/**
	 * What follows an experiment's head: the objective, the tier criteria and the knee,
	 * the repeatability and the spread, the p95 at or below the knee, the steps and the
	 * resources.
	 */
	private static final class Body {

		private Body() {
		}

		static void add(ObjectNode json, JsonNode comparison, JsonNode criteria, List<JsonNode> runs) {
			JsonNode error = comparison.get("kneeError");
			Optional<ObjectNode> spread = KneeSpread.of(runs);
			json.put("tier", criteria.get("all").booleanValue() ? "validated" : "not-validated");
			json.set("criteria", criteria);
			json.set("knee", knee(comparison));
			json.set("kneeRange", PythonValues.get(comparison, "kneeRange"));
			json.set("disturbed", RunHistory.disturbed(comparison.get("rows").valueStream().toList()));
			json.set("repeatability", PythonValues.orNone(Repeatability.of(runs)));
			json.set("spread", PythonValues.orNone(spread));
			json.put("spreadExceedsError", spread.isPresent() && !PythonValues.isNone(error)
					&& spread.get().get("plusMinus").doubleValue() > Math.abs(error.doubleValue()));
			json.set("p95BelowKnee", p95BelowKnee(comparison, criteria));
			ArrayNode lowLoad = json.putArray("lowLoad");
			SummaryBlocks.lowLoadRows(comparison)
				.forEach((row) -> lowLoad.add(AccuracySteps.lowLoad(row, comparison.get("predictedKneeFine"))));
			json.set("steps", steps(comparison));
			json.set("resources", AccuracySteps.resources(comparison));
		}

		private static ObjectNode knee(JsonNode comparison) {
			JsonNode measured = comparison.get("measuredKnee");
			JsonNode predicted = comparison.get("predictedKnee");
			JsonNode error = comparison.get("kneeError");
			ObjectNode knee = NODES.objectNode().set("measured", measured);
			knee.set("predicted", predicted);
			knee.set("searchedRps", comparison.get("predictedKneeFine"));
			knee.set("error", error);
			knee.put("withinTolerance",
					!PythonValues.isNone(error) && Math.abs(error.doubleValue()) <= Tiers.KNEE_TOLERANCE);
			knee.putObject("text")
				.put("measured", Figures.knee(measured))
				.put("predicted", Figures.knee(predicted))
				.put("error", Figures.percent(error))
				.put("absError", Figures.magnitude(PythonValues.isNone(error) ? error : PythonValues.abs(error)));
			return knee;
		}

		private static ObjectNode p95BelowKnee(JsonNode comparison, JsonNode criteria) {
			JsonNode below = comparison.get("belowKnee");
			JsonNode misses = criteria.at("/p95/misses");
			ObjectNode json = NODES.objectNode().set("steps", below.get("steps"));
			json.put("within", below.get("steps").intValue() - misses.size());
			json.set("misses", misses);
			json.set("pass", criteria.at("/p95/pass"));
			json.set("medianAbsError", below.get("medianAbsP95Error"));
			json.set("maxAbsError", below.get("maxAbsP95Error"));
			json.set("worst", worst(comparison).<JsonNode>map((row) -> AccuracySteps.step(row, true))
				.orElse(PythonValues.none()));
			json.putObject("text")
				.put("medianAbsError", Figures.magnitude(below.get("medianAbsP95Error")))
				.put("maxAbsError", Figures.magnitude(below.get("maxAbsP95Error")));
			return json;
		}

		// ranked as criterion 2 judges a step: by the factor, so
		// half counts as far off as double; the first of the worst
		private static Optional<JsonNode> worst(JsonNode comparison) {
			JsonNode worst = null;
			double largest = 0;
			for (JsonNode row : atOrBelowKnee(comparison)) {
				if (PythonValues.isNone(row.get("error").get("p95"))) {
					continue;
				}
				double factor = Tiers.p95Factor(row.get("measured").get("p95").doubleValue(),
						row.get("predicted").get("p95").doubleValue());
				if (worst == null || factor > largest) {
					worst = row;
					largest = factor;
				}
			}
			return Optional.ofNullable(worst);
		}

		private static ArrayNode steps(JsonNode comparison) {
			Set<JsonNode> below = Collections.newSetFromMap(new IdentityHashMap<>());
			below.addAll(atOrBelowKnee(comparison));
			ArrayNode steps = NODES.arrayNode();
			comparison.get("rows").forEach((row) -> steps.add(AccuracySteps.step(row, below.contains(row))));
			return steps;
		}

		private static List<JsonNode> atOrBelowKnee(JsonNode comparison) {
			return Knees.atOrBelowKnee(comparison.get("rows").valueStream().toList(), comparison.get("measuredKnee"));
		}

	}

	/**
	 * An experiment's latest run's files.
	 *
	 * @param calibration its {@code calibration.json}
	 * @param predictions its {@code predictions.json}
	 * @param comparison its {@code comparison.json}
	 */
	private record Latest(JsonNode calibration, JsonNode predictions, JsonNode comparison) {

	}

}
