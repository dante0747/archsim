package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.baselines.Baselines;
import io.archsim.benchmarks.comparison.RelativeError;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The validation page's "Against simple estimates" block (ADR-0087): each experiment's
 * latest run with its measured knee, the simulator's predicted knee and each baseline's
 * ({@code baselines.json}), every one with its error, and under the table the median
 * absolute error of each over the held-out and over the tuned-on experiments. It shows
 * whatever comes out, a baseline that beats the simulator included.
 */
final class BaselinesBlock {

	private static final String SIMULATOR = "simulator";

	/** The samples, in the order of the page: the held-out experiments first. */
	private static final List<String> SAMPLES = List.of("held-out", "tuned-on");

	private static final BaselineTexts TEXTS = BaselineTexts.standard();

	private static final String PARAGRAPH = "\n\n";

	private BaselinesBlock() {
	}

	/**
	 * The block of the latest runs under a results folder.
	 * @param comparisons the latest runs' comparisons, in the page's order
	 * @param sources the results, where each run's baselines are, and the engines
	 * @return the block; that there are no results yet when there are none
	 */
	static String of(List<JsonNode> comparisons, ReportSources sources) {
		if (comparisons.isEmpty()) {
			return ValidationPage.NO_RESULTS;
		}
		Map<String, JsonNode> baselines = new LinkedHashMap<>();
		for (JsonNode comparison : comparisons) {
			String experiment = comparison.get("experiment").stringValue();
			LatestRuns.load(sources.results(), experiment, KeptRun.BASELINES)
				.ifPresent((file) -> baselines.put(experiment, file));
		}
		return of(comparisons, ValidationPage.samples(comparisons, sources), baselines);
	}

	/**
	 * The block of some runs.
	 * @param comparisons the runs' comparisons, in the page's order
	 * @param samples each experiment's sample label
	 * @param baselines each experiment's {@code baselines.json}, for those that have one
	 * @return what the estimates are, the table, each sample's medians and the notes
	 */
	static String of(List<JsonNode> comparisons, Map<String, String> samples, Map<String, JsonNode> baselines) {
		List<Row> rows = comparisons.stream().map((comparison) -> Row.of(comparison, samples, baselines)).toList();
		List<String> sections = new ArrayList<>(List.of(TEXTS.intro(), table(rows)));
		SAMPLES.forEach((sample) -> medians(rows, sample).ifPresent(sections::add));
		sections.addAll(notes(rows, baselines));
		return String.join(PARAGRAPH, sections);
	}

	private static String table(List<Row> rows) {
		List<String> titles = new ArrayList<>(List.of("Experiment", "Sample", "Knee measured", TEXTS.name(SIMULATOR)));
		Baselines.ESTIMATES.forEach((estimate) -> titles.add(TEXTS.name(estimate)));
		List<String> lines = new ArrayList<>();
		lines.add("| " + String.join(" | ", titles) + " |");
		lines.add("|" + " --- |".repeat(titles.size()));
		rows.forEach((row) -> lines.add(row.line()));
		return String.join("\n", lines);
	}

	// the median absolute error of the simulator and of each estimate over a sample
	private static Optional<String> medians(List<Row> rows, String sample) {
		List<Row> sampled = rows.stream().filter((row) -> sample.equals(row.sample())).toList();
		if (sampled.isEmpty()) {
			return Optional.empty();
		}
		List<String> medians = new ArrayList<>();
		medians.add(inSentence(TEXTS.name(SIMULATOR)) + " " + median(sampled, 0));
		for (int column = 1; column <= Baselines.ESTIMATES.size(); column++) {
			medians.add(inSentence(TEXTS.name(Baselines.ESTIMATES.get(column - 1))) + " " + median(sampled, column));
		}
		String counted = sampled.size() + ((sampled.size() == 1) ? " experiment" : " experiments");
		return Optional.of("Median absolute knee error, " + SummaryBlocks.SAMPLE_TEXT.get(sample) + " (" + counted
				+ "): " + String.join(", ", medians) + ".");
	}

	// over the runs that have the figure, and how many they are when not all
	private static String median(List<Row> rows, int column) {
		List<JsonNode> errors = rows.stream()
			.map((row) -> row.knees().get(column).error())
			.filter((error) -> !PythonValues.isNone(error))
			.map(PythonValues::abs)
			.toList();
		if (errors.isEmpty()) {
			return Figures.NONE;
		}
		String median = Figures.magnitude(PythonValues.median(errors));
		return (errors.size() < rows.size()) ? median + " (" + errors.size() + " of " + rows.size() + ")" : median;
	}

	// what the estimates are evidence of, an estimate that calls its design overloaded at
	// its knee, and the runs without baselines
	private static List<String> notes(List<Row> rows, Map<String, JsonNode> baselines) {
		List<String> notes = new ArrayList<>();
		if (!baselines.isEmpty()) {
			notes.add(standing(rows, baselines));
		}
		List<String> missing = new ArrayList<>();
		for (Row row : rows) {
			JsonNode atKnee = estimate(baselines.get(row.experiment())).path("atKnee");
			if (!baselines.containsKey(row.experiment())) {
				missing.add(row.experiment());
			}
			else if (atKnee.path("overloaded").asBoolean(false)) {
				notes.add(TEXTS.overloaded(row.experiment(), atKnee.get("summary").stringValue()));
			}
		}
		if (!missing.isEmpty()) {
			notes.add(TEXTS.missing(Figures.andList(missing)));
		}
		return notes;
	}

	// when and by which engine the estimates were computed, when their rules were fixed,
	// which runs shown were calibrated after that, and where the figures are
	private static String standing(List<Row> rows, Map<String, JsonNode> baselines) {
		List<String> sentences = new ArrayList<>();
		sentences.add(TEXTS.computed(listed(baselines, (file) -> Figures.date(file.get("at").stringValue())),
				listed(baselines, (file) -> file.get("engineVersion").stringValue())));
		sentences.add(TEXTS.rules(listed(baselines, (file) -> file.get("rulesFixed").stringValue())));
		List<String> later = rows.stream()
			.map(Row::experiment)
			.filter((experiment) -> calibratedAfterTheRules(baselines.get(experiment)))
			.toList();
		if (!later.isEmpty()) {
			sentences.add(TEXTS.later(Figures.andList(later)));
		}
		sentences.add(TEXTS.files());
		return String.join(" ", sentences);
	}

	private static boolean calibratedAfterTheRules(@Nullable JsonNode file) {
		return file != null && !file.path("designedAfterMeasurements").asBoolean(true);
	}

	// the engine's analytic estimate in a run's baselines; nothing for a run without them
	private static JsonNode estimate(@Nullable JsonNode file) {
		return (file != null) ? file.path("baselines").path(Baselines.ESTIMATE) : PythonValues.none();
	}

	// something each file says, each value once, in order, as a sentence lists them
	private static String listed(Map<String, JsonNode> baselines, Function<JsonNode, String> said) {
		return Figures.andList(baselines.values().stream().map(said).distinct().sorted().toList());
	}

	// a column's title inside a sentence
	private static String inSentence(String title) {
		return Character.toLowerCase(title.charAt(0)) + title.substring(1);
	}

	/**
	 * An experiment's row.
	 *
	 * @param experiment the experiment
	 * @param sample its sample label
	 * @param measured the measured knee, as the comparison holds it
	 * @param knees the simulator's knee, then each estimate's
	 */
	private record Row(String experiment, String sample, JsonNode measured, List<Knee> knees) {

		static Row of(JsonNode comparison, Map<String, String> samples, Map<String, JsonNode> baselines) {
			String experiment = comparison.get("experiment").stringValue();
			JsonNode measured = comparison.get("measuredKnee");
			JsonNode file = baselines.get(experiment);
			List<Knee> knees = new ArrayList<>();
			knees.add(
					new Knee(Figures.knee(comparison.get("predictedKnee")), PythonValues.get(comparison, "kneeError")));
			for (String estimate : Baselines.ESTIMATES) {
				JsonNode knee = (file != null) ? file.path("baselines").path(estimate).path(Baselines.KNEE)
						: PythonValues.none();
				knees.add(new Knee(Figures.rate(knee), RelativeError.of(knee, PythonValues.get(measured, "kneeRps"))));
			}
			return new Row(experiment, Objects.requireNonNull(samples.get(experiment), experiment), measured, knees);
		}

		String line() {
			return "| [" + this.experiment + "](#" + this.experiment + ") | "
					+ SummaryBlocks.SAMPLE_TEXT.get(this.sample) + " | " + Figures.knee(this.measured) + " req/s | "
					+ String.join(" | ", this.knees.stream().map(Knee::cell).toList()) + " |";
		}

	}

	/**
	 * A predicted or estimated knee.
	 *
	 * @param rate its load as the page writes it, a dash for none
	 * @param error its error against the measured knee, or None
	 */
	private record Knee(String rate, JsonNode error) {

		String cell() {
			boolean bare = Figures.NONE.equals(this.rate) || PythonValues.isNone(this.error);
			return bare ? this.rate : this.rate + " (" + Figures.percent(this.error) + ")";
		}

	}

}
