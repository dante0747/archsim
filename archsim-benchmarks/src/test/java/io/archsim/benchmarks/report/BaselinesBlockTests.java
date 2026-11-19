package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validation page's "Against simple estimates" block: each latest run's measured
 * knee, the simulator's and each baseline's with its error, and each sample's median
 * absolute error; on hand-made runs, and on kept runs of the committed results.
 */
class BaselinesBlockTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Map<String, String> TUNED_ON = Map.of("pg-mixed", "tuned-on", "kafka", "tuned-on");

	/** Measured at 1,000 req/s: the simulator 10% over, the arithmetic 10% under. */
	private static final JsonNode PG_MIXED = comparison("pg-mixed", 1000.0, 1100.0, 0.1);

	private static final JsonNode PG_MIXED_BASELINES = baselines(900.0, "1250", null, false);

	/** Measured at 2,000 req/s: the simulator 30% under. */
	private static final JsonNode KAFKA = comparison("kafka", 2000.0, 1400.0, -0.3);

	private static final JsonNode KAFKA_BASELINES = baselines(2400.0, "2100", 2800.0, true);

	/**
	 * A run of each experiment in a {@code run-N/} folder, which no later campaign
	 * changes, in the page's order.
	 */
	private static final Map<String, String> KEPT_RUNS = keptRuns();

	@Test
	void of_aRunWithBaselines_setsEachEstimatesKneeAndErrorBesideTheSimulators() {
		String block = BaselinesBlock.of(List.of(PG_MIXED), TUNED_ON, Map.of("pg-mixed", PG_MIXED_BASELINES));

		assertThat(block).contains("""
				| Experiment | Sample | Knee measured | Simulator | Bottleneck arithmetic | Engine's analytic estimate \
				| Utilisation extrapolation |
				| --- | --- | --- | --- | --- | --- | --- |
				| [pg-mixed](#pg-mixed) | tuned on | 1,000 req/s | 1,100 (+10%) | 900 (-10%) | 1,250 (+25%) | - |
				""");
	}

	// the simulator's 10% and 30%, the arithmetic's 10% and 20%, the estimate's 25% and
	// 5%
	@Test
	void of_runsOfOneSample_givesTheMedianAbsoluteErrorOfTheSimulatorAndOfEachEstimate() {
		String block = BaselinesBlock.of(List.of(PG_MIXED, KAFKA), TUNED_ON,
				Map.of("pg-mixed", PG_MIXED_BASELINES, "kafka", KAFKA_BASELINES));

		assertThat(block).contains("Median absolute knee error, tuned on (2 experiments): simulator 20%, bottleneck "
				+ "arithmetic 15%, engine's analytic estimate 15%, utilisation extrapolation 40% (1 of 2).");
	}

	@Test
	void of_runsOfBothSamples_summarisesTheHeldOutOnesFirstAndApart() {
		String block = BaselinesBlock.of(List.of(KAFKA, PG_MIXED), Map.of("pg-mixed", "tuned-on", "kafka", "held-out"),
				Map.of("pg-mixed", PG_MIXED_BASELINES, "kafka", KAFKA_BASELINES));

		assertThat(block).contains("""
				Median absolute knee error, held out (1 experiment): simulator 30%, bottleneck arithmetic 20%, \
				engine's analytic estimate 5%, utilisation extrapolation 40%.

				Median absolute knee error, tuned on (1 experiment): simulator 10%, bottleneck arithmetic 10%, \
				engine's analytic estimate 25%, utilisation extrapolation -.
				""");
	}

	@Test
	void of_anyRuns_opensWithWhatEachEstimateIs() {
		String block = BaselinesBlock.of(List.of(PG_MIXED), TUNED_ON, Map.of("pg-mixed", PG_MIXED_BASELINES));

		assertThat(block).startsWith("Would simple arithmetic on the same calibration find the knee")
			.contains("- **Bottleneck arithmetic.** ")
			.contains("- **Engine's analytic estimate.** ")
			.contains("- **Utilisation extrapolation.** ");
	}

	@Test
	void of_runsWithBaselines_saysWhenAndByWhichEngineTheyWereComputedAndThatTheyAreNoPredictions() {
		String block = BaselinesBlock.of(List.of(PG_MIXED), TUNED_ON, Map.of("pg-mixed", PG_MIXED_BASELINES));

		assertThat(block).contains("computed afterwards (on 2026-11-19; the analytic estimate by engine 1.5.0)")
			.contains("Their rules were fixed on 2026-11-19, with the results of every run calibrated before that "
					+ "day in view")
			.contains("are not predictions")
			.doesNotContain("were calibrated after it");
	}

	// a later campaign's run: the rules predate what it measured
	@Test
	void of_aRunCalibratedAfterTheRulesWereFixed_saysTheRulesPredateItsMeasurement() {
		ObjectNode later = ((ObjectNode) KAFKA_BASELINES).deepCopy().put("designedAfterMeasurements", false);

		String block = BaselinesBlock.of(List.of(PG_MIXED, KAFKA), TUNED_ON,
				Map.of("pg-mixed", PG_MIXED_BASELINES, "kafka", later));

		assertThat(block)
			.contains("The runs shown of kafka were calibrated after it: for them the rules predate the measurement");
	}

	@Test
	void of_anEstimateThatCallsTheDesignOverloadedAtItsKnee_quotesIt() {
		String block = BaselinesBlock.of(List.of(KAFKA), TUNED_ON, Map.of("kafka", KAFKA_BASELINES));

		assertThat(block).contains("At kafka's knee the engine's estimate already calls the design overloaded, for "
				+ "work no request waits for (\"Overloaded at 2,100 req/s.\")");
	}

	@Test
	void of_aRunWithoutBaselines_showsNoEstimateAndNamesTheCommandThatWritesThem() {
		String block = BaselinesBlock.of(List.of(PG_MIXED), TUNED_ON, Map.of());

		assertThat(block).contains("| [pg-mixed](#pg-mixed) | tuned on | 1,000 req/s | 1,100 (+10%) | - | - | - |")
			.endsWith("No baselines yet for pg-mixed: run `./gradlew :archsim-benchmarks:run --args=\"baselines\"`.");
	}

	@Test
	void of_noResults_saysSo() {
		String block = BaselinesBlock.of(List.of(), sources());

		assertThat(block).isEqualTo("_No results yet._");
	}

	// pg-hot-row: the lock's arithmetic is the simulator's knee; the others miss the lock
	@Test
	void of_keptRunsOfTheCommittedResults_showsEachRunsKnees() {
		String block = keptRunsBlock();

		assertThat(block)
			.contains("| [pg-hot-row](#pg-hot-row) | tuned on | 947 req/s | 863 (-9%) | 866 (-9%) | 1,725 (+82%) | "
					+ "3,711 (+292%) |")
			.contains("| [go-mixed](#go-mixed) | held out | 6,204 req/s | 9,802 (+58%) | 9,796 (+58%) | 8,350 (+35%) | "
					+ "10,051 (+62%) |");
	}

	@Test
	void of_keptRunsOfTheCommittedResults_givesEachSamplesMedians() {
		String block = keptRunsBlock();

		assertThat(block)
			.contains("Median absolute knee error, held out (5 experiments): simulator 30%, bottleneck arithmetic "
					+ "30%, engine's analytic estimate 30%, utilisation extrapolation 33%.")
			.contains("Median absolute knee error, tuned on (4 experiments): simulator 13%, bottleneck arithmetic "
					+ "14%, engine's analytic estimate 18%, utilisation extrapolation 18%.");
	}

	// the block of one kept run of each experiment, as its comparison and baselines hold
	// it
	private static String keptRunsBlock() {
		List<JsonNode> comparisons = new ArrayList<>();
		Map<String, String> samples = new LinkedHashMap<>();
		Map<String, JsonNode> baselines = new LinkedHashMap<>();
		KEPT_RUNS.forEach((experiment, run) -> {
			Path folder = LAYOUT.results().resolve(experiment).resolve(run);
			comparisons.add(ResultFiles.read(folder.resolve("comparison.json")).orElseThrow());
			samples.put(experiment, Experiments.standard().get(experiment).isHeldOut() ? "held-out" : "tuned-on");
			baselines.put(experiment, ResultFiles.read(folder.resolve("baselines.json")).orElseThrow());
		});
		return BaselinesBlock.of(comparisons, samples, baselines);
	}

	private static ReportSources sources() {
		return new ReportSources(LAYOUT.results(), Engines.read(LAYOUT.engines()),
				Cpusets.declaredIn(LAYOUT.compose()));
	}

	private static JsonNode comparison(String experiment, double measured, double predicted, double error) {
		return JsonText.read("""
				{"experiment": "%s", "measuredKnee": {"kneeRps": %s}, "predictedKnee": {"kneeRps": %s},
				 "kneeError": %s}
				""".formatted(experiment, measured, predicted, error));
	}

	// a run's baselines: the estimate's knee as the search writes it, an integer
	private static JsonNode baselines(double arithmetic, String estimate, @Nullable Double extrapolation,
			boolean overloaded) {
		return JsonText.read("""
				{"at": "2026-11-19T01:24:00Z", "engineVersion": "1.5.0", "rulesFixed": "2026-11-19",
				 "designedAfterMeasurements": true,
				 "baselines": {
				  "bottleneckArithmetic": {"kneeRps": %s},
				  "analyticEstimate": {"kneeRps": %s,
				   "atKnee": {"overloaded": %s, "summary": "Overloaded at 2,100 req/s."}},
				  "utilisationExtrapolation": {"kneeRps": %s}}}
				""".formatted(arithmetic, estimate, overloaded, extrapolation));
	}

	private static Map<String, String> keptRuns() {
		Map<String, String> runs = new LinkedHashMap<>();
		List.of("java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl")
			.forEach((heldOut) -> runs.put(heldOut, "run-2"));
		runs.put("pg-mixed", "run-15");
		runs.put("pg-hot-row", "run-13");
		runs.put("cache-aside", "run-10");
		runs.put("kafka", "run-10");
		return runs;
	}

}
