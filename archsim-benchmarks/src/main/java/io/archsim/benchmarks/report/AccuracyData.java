package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonJson;
import io.archsim.model.text.PythonText;

/**
 * Every number of the validation page's generated blocks as data, {@code accuracy.json},
 * which the web app's {@code /accuracy} page reads: the latest run of each experiment, a
 * summary across them, every kept run, and what the Java engine reproduces.
 */
final class AccuracyData {

	/** What wrote the file, as the file says. */
	static final String GENERATED_BY = "./gradlew :archsim-benchmarks:run --args=\"report --json\", from "
			+ "benchmarks/results";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int INDENT = 2;

	private AccuracyData() {
	}

	/**
	 * The file's text: {@code json.dumps(data, indent=2, ensure_ascii=False)} and a
	 * newline.
	 * @param sources the results and what labels them
	 * @return the text
	 */
	static String text(ReportSources sources) {
		return PythonJson.writeIndentedAsIs(PythonValues.orNone(of(sources)), INDENT) + "\n";
	}

	/**
	 * The data.
	 * @param sources the results and what labels them
	 * @return the data; empty without results
	 */
	static Optional<ObjectNode> of(ReportSources sources) {
		Map<String, List<JsonNode>> history = RunHistory.of(sources.results());
		List<ObjectNode> experiments = new ArrayList<>();
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			AccuracyExperiment.of(experiment, history.getOrDefault(experiment.id(), List.of()), sources)
				.ifPresent(experiments::add);
		}
		if (experiments.isEmpty()) {
			return Optional.empty();
		}
		ObjectNode data = NODES.objectNode().put("generatedBy", GENERATED_BY);
		data.set("objective", experiments.getFirst().get("objective"));
		data.putObject("thresholds")
			.put("kneeError", Tiers.KNEE_TOLERANCE)
			.put("p95Factor", Tiers.P95_FACTOR)
			.put("p95Ms", Tiers.P95_TOLERANCE_MS)
			.put("lowLoadFraction", SummaryBlocks.LOW_LOAD_FRACTION);
		data.set("summary", summary(experiments, history));
		data.putArray("experiments").addAll(experiments);
		data.set("history", history(history));
		data.set("javaEngine", PythonValues.orNone(JavaEngineCheck.of(sources.results(), sources.engines())));
		return Optional.of(data);
	}

	private static ObjectNode summary(List<ObjectNode> experiments, Map<String, List<JsonNode>> history) {
		List<JsonNode> runs = allRuns(history);
		List<JsonNode> errors = experiments.stream()
			.map((experiment) -> experiment.at("/knee/error"))
			.filter((error) -> !PythonValues.isNone(error))
			.toList();
		JsonNode low = errors.isEmpty() ? PythonValues.none() : PythonValues.min(errors);
		JsonNode high = errors.isEmpty() ? PythonValues.none() : PythonValues.max(errors);
		ObjectNode summary = NODES.objectNode().put("experiments", experiments.size()).put("runs", runs.size());
		summary.set("firstDate", firstDate(runs));
		summary.put("latestDate",
				experiments.stream()
					.map((experiment) -> experiment.get("date").stringValue())
					.max(String::compareTo)
					.orElseThrow());
		ArrayNode machines = summary.putArray("machines");
		Set<String> distinct = new LinkedHashSet<>();
		runs.forEach((run) -> distinct.add(run.get("machine").stringValue()));
		distinct.forEach(machines::add);
		summary.set("kneeErrorMin", low);
		summary.set("kneeErrorMax", high);
		summary.put("kneeErrorRangeText", Figures.percent(low) + " to " + Figures.percent(high));
		Lists.add(summary, experiments, history);
		return summary;
	}

	private static JsonNode firstDate(List<JsonNode> runs) {
		return runs.stream()
			.map((run) -> run.get("calibratedAt"))
			.filter(PythonText::truthy)
			.map((at) -> Figures.date(at.stringValue()))
			.min(String::compareTo)
			.<JsonNode>map(NODES::stringNode)
			.orElse(PythonValues.none());
	}

	private static List<JsonNode> allRuns(Map<String, List<JsonNode>> history) {
		List<JsonNode> runs = new ArrayList<>();
		LatestRuns.inPageOrder().forEach((experiment) -> runs.addAll(history.getOrDefault(experiment.id(), List.of())));
		return runs;
	}

	// every kept run in the page's order, each with the history table's text
	private static ObjectNode history(Map<String, List<JsonNode>> history) {
		ObjectNode json = NODES.objectNode();
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			List<JsonNode> runs = history.get(experiment.id());
			if (runs == null) {
				continue;
			}
			ArrayNode array = json.putArray(experiment.id());
			for (JsonNode run : runs) {
				ObjectNode entry = ((ObjectNode) run).deepCopy();
				entry.putObject("text")
					.put("measuredKnee", Figures.knee(run.get("measuredKnee")))
					.put("predictedKnee", Figures.knee(run.get("predictedKnee")))
					.put("kneeError", Figures.percent(run.get("kneeError")));
				array.add(entry);
			}
		}
		return json;
	}

	/**
	 * The summary's lists of experiments: which meet each threshold, which have a spread,
	 * which are in-sample, held out or repeated, the coverage, the disturbed runs and the
	 * technologies.
	 */
	private static final class Lists {

		private Lists() {
		}

		static void add(ObjectNode summary, List<ObjectNode> experiments, Map<String, List<JsonNode>> history) {
			summary.set("kneeWithinTolerance",
					ids(experiments, (experiment) -> experiment.at("/knee/withinTolerance").booleanValue()));
			summary.set("p95WithinTolerance",
					ids(experiments, (experiment) -> experiment.at("/p95BelowKnee/pass").booleanValue()));
			summary.set("validated",
					ids(experiments, (experiment) -> experiment.get("tier").stringValue().equals("validated")));
			summary.set("withSpread", ids(experiments, (experiment) -> PythonText.truthy(experiment.get("spread"))));
			summary.set("spreadExceedsError",
					ids(experiments, (experiment) -> experiment.get("spreadExceedsError").booleanValue()));
			summary.set("inSample", ids(experiments, (experiment) -> experiment.get("inSample").booleanValue()));
			summary.set("caveats", ids(experiments, (experiment) -> PythonText.truthy(experiment.get("caveat"))));
			summary.set("repeated", ids(experiments,
					(experiment) -> experiment.path("repeatability").path("runs").asInt(0) >= Tiers.MIN_RUNS));
			summary.set("coverage", Repeatability.coverageSummary(repeatabilities(experiments, (experiment) -> true)));
			summary.set("coverageHeldOut", Repeatability.coverageSummary(repeatabilities(experiments, Lists::heldOut)));
			summary.put("disturbedRuns",
					(int) allRuns(history).stream().filter((run) -> !RunHistory.counted(run)).count());
			summary.set("heldOut", ids(experiments, Lists::heldOut));
			Map<String, Boolean> met = new LinkedHashMap<>();
			experiments.forEach((experiment) -> met.put(experiment.get("id").stringValue(),
					experiment.at("/criteria/all").booleanValue()));
			summary.set("technologies", Tiers.technologyStatus(met));
		}

		private static boolean heldOut(JsonNode experiment) {
			return experiment.get("sample").stringValue().equals("held-out");
		}

		private static ArrayNode ids(List<ObjectNode> experiments, Predicate<JsonNode> which) {
			ArrayNode ids = NODES.arrayNode();
			experiments.stream().filter(which).forEach((experiment) -> ids.add(experiment.get("id")));
			return ids;
		}

		private static List<JsonNode> repeatabilities(List<ObjectNode> experiments, Predicate<JsonNode> which) {
			return experiments.stream().filter(which).map((experiment) -> experiment.get("repeatability")).toList();
		}

	}

}
