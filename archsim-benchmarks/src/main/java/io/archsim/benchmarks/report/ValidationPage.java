package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * {@code docs/validation.md}'s generated blocks, rewritten from the results: only the
 * text between {@code <!-- BEGIN GENERATED: name -->} and
 * {@code <!-- END GENERATED: name -->} is replaced, so the hand-written text is kept. The
 * output depends only on the results, so rendering twice gives the same bytes.
 */
final class ValidationPage {

	private static final String NO_RESULTS = "_No results yet._";

	private ValidationPage() {
	}

	/**
	 * The page with its generated blocks rewritten, and the charts.
	 * @param page the page as it is
	 * @param sources the results and what labels them
	 * @return the page and each chart's SVG by file name
	 * @throws IllegalStateException when the page lacks a generated block
	 */
	static Reports.Page render(String page, ReportSources sources) {
		Path results = sources.results();
		List<Experiment> order = LatestRuns.inPageOrder();
		List<JsonNode> comparisons = order.stream()
			.map((experiment) -> LatestRuns.load(results, experiment.id(), KeptRun.COMPARISON))
			.flatMap(Optional::stream)
			.toList();
		Map<String, List<JsonNode>> history = RunHistory.of(results);
		String text = page;
		text = replace(text, "summary",
				comparisons.isEmpty() ? NO_RESULTS : SummaryBlocks.summary(comparisons, samples(comparisons, sources)));
		text = replace(text, "repeatability",
				history.isEmpty() ? NO_RESULTS : SummaryBlocks.repeatability(history, sources.engines()));
		text = replace(text, "history", history.isEmpty() ? NO_RESULTS : SummaryBlocks.history(history));
		text = replace(text, "low-load", comparisons.isEmpty() ? NO_RESULTS : SummaryBlocks.lowLoad(comparisons));
		text = replace(text, "resources", ResourcesBlock.of(comparisons));
		text = replace(text, "tiers", comparisons.isEmpty() ? NO_RESULTS : TiersBlock.of(comparisons, history));
		text = replace(text, "environment", EnvironmentBlock.of(environments(results), sources.cpusets()));
		text = replace(text, "java-engine", JavaEngineCheck.block(results, sources.engines()));
		Map<String, String> charts = new LinkedHashMap<>();
		for (Experiment experiment : order) {
			text = replace(text, experiment.id(), ExperimentBlock.of(experiment, results));
			LatestRuns.load(results, experiment.id(), KeptRun.COMPARISON)
				.ifPresent((comparison) -> charts.put(experiment.id() + ".svg", Chart.of(comparison)));
		}
		return new Reports.Page(text, charts);
	}

	/**
	 * Each experiment's latest run's sample label: held out or tuned on, for the engine
	 * that predicted it.
	 * @param comparisons the latest runs' comparisons
	 * @param sources the results and the engines
	 * @return each experiment's label
	 */
	static Map<String, String> samples(List<JsonNode> comparisons, ReportSources sources) {
		Map<String, String> samples = new LinkedHashMap<>();
		for (JsonNode comparison : comparisons) {
			String experiment = comparison.get("experiment").stringValue();
			JsonNode engine = LatestRuns.load(sources.results(), experiment, KeptRun.PREDICTIONS)
				.map((predictions) -> PythonValues.get(predictions, "engineVersion"))
				.orElse(PythonValues.none());
			samples.put(experiment, sources.engines().sampleLabel(engine, Experiments.standard().get(experiment)));
		}
		return samples;
	}

	// each experiment's latest environment.json that holds anything, in the page's order
	private static Map<String, JsonNode> environments(Path results) {
		Map<String, JsonNode> environments = new LinkedHashMap<>();
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			LatestRuns.load(results, experiment.id(), KeptRun.ENVIRONMENT)
				.filter(PythonText::truthy)
				.ifPresent((environment) -> environments.put(experiment.id(), environment));
		}
		return environments;
	}

	/**
	 * A page with one generated block's body replaced, wherever the block is.
	 * @param page the page
	 * @param name the block's name
	 * @param body its new body
	 * @return the page
	 * @throws IllegalStateException when the page has no such block
	 */
	static String replace(String page, String name, String body) {
		Pattern block = Pattern.compile("(<!-- BEGIN GENERATED: " + Pattern.quote(name)
				+ " -->\n).*?(\n<!-- END GENERATED: " + Pattern.quote(name) + " -->)", Pattern.DOTALL);
		Matcher matcher = block.matcher(page);
		if (!matcher.find()) {
			throw new IllegalStateException("docs/validation.md has no generated block '" + name + "'");
		}
		return matcher.replaceAll((found) -> Matcher.quoteReplacement(found.group(1) + body + found.group(2)));
	}

}
