package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The pages and data the harness writes from its results, each as text to write as it is:
 * {@code docs/validation.md} and its charts, {@code accuracy.json}, the development
 * checks' page and the knee range's (ADR-0070).
 */
public final class Reports {

	private final ReportSources sources;

	private final Path kneeRange;

	/**
	 * Reports from the repository's files.
	 * @param results the results folder
	 * @param engines {@code benchmarks/engines.json}
	 * @param compose {@code benchmarks/compose.yaml}, whose cpusets are the runner's
	 * defaults
	 * @param kneeRange {@code benchmarks/knee-range.json}
	 */
	public Reports(Path results, Path engines, Path compose, Path kneeRange) {
		this.sources = new ReportSources(results, Engines.read(engines), Cpusets.declaredIn(compose));
		this.kneeRange = kneeRange;
	}

	/**
	 * The validation page with its generated blocks rewritten, and its charts.
	 * @param page {@code docs/validation.md} as it is
	 * @return the page and its charts
	 */
	public Page validationPage(String page) {
		return ValidationPage.render(page, this.sources);
	}

	/**
	 * {@code accuracy.json}'s text.
	 * @return the text
	 */
	public String accuracy() {
		return AccuracyData.text(this.sources);
	}

	/**
	 * {@code docs/validation/dev-checks.md}'s text.
	 * @return the text
	 */
	public String devChecks() {
		return DevChecksPage.text(this.sources.results());
	}

	/**
	 * {@code docs/validation/knee-range.md}'s text.
	 * @return the text
	 */
	public String kneeRange() {
		return KneeRangePage.text(ResultFiles.read(this.kneeRange).orElse(PythonValues.none()));
	}

	/**
	 * The validation page rendered.
	 *
	 * @param text the page's text
	 * @param charts each chart's SVG, by its file name in {@code docs/validation/}, in
	 * the page's order
	 */
	public record Page(String text, Map<String, String> charts) {

		public Page {
			charts = Collections.unmodifiableMap(new LinkedHashMap<>(charts));
		}

	}

}
