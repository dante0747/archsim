package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The held-out runs' knees against the simple estimates', on runs written here, each of
 * which measured a knee of 1,000 req/s.
 */
class AgainstEstimatesHeldOutTests {

	private static final List<String> GO_POOL = List.of("go-pool");

	@Test
	void of_noHeldOutExperiment_isEmpty(@TempDir Path results) {
		assertThat(AgainstEstimatesHeldOut.of(List.of(), Map.of(), results)).isEmpty();
	}

	@Test
	void of_threeRuns_giveTheSimulatorsMedianAndWorstError(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.get("simulator")).isEqualTo(JsonText.read("""
				{"runs": 3, "medianAbsError": 0.2, "worstError": -0.3,
				 "worst": {"experiment": "go-pool", "dir": "run-2/"},
				 "text": {"medianAbsError": "20.0%", "worstError": "-30%"}}"""));
	}

	// the arithmetic's errors are +15%, +50% and -10%, a median size of 15%, where the
	// simulator's +10%, -30% and +20% have one of 20%: the experiment counts for the
	// arithmetic, though the simulator is nearer on two of its three runs
	@Test
	void of_threeRuns_giveAnEstimatesMedianAndWorstErrorAndWhichWasNearer(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.at("/estimates/0")).isEqualTo(JsonText.read("""
				{"estimate": "bottleneckArithmetic", "name": "Bottleneck arithmetic", "runs": 3,
				 "medianAbsError": 0.15, "worstError": 0.5,
				 "worst": {"experiment": "go-pool", "dir": "run-2/"},
				 "simulatorNearer": 0, "estimateNearer": 1, "verdict": "level",
				 "text": {"medianAbsError": "15.0%", "worstError": "+50%"}}"""));
	}

	@Test
	void of_threeRuns_compareTheSimulatorWithEachEstimateInTheFilesOrder(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.get("runs").intValue()).isEqualTo(3);
		assertThat(comparison.get("experiments").intValue()).isEqualTo(1);
		assertThat(comparison.get("estimates")).extracting((estimate) -> estimate.get("estimate").stringValue())
			.containsExactly("bottleneckArithmetic", "analyticEstimate", "utilisationExtrapolation");
	}

	// cache-miss's one run, at -40%, is the worst of the four
	@Test
	void of_twoHeldOutExperiments_poolTheirRuns(@TempDir Path results) throws IOException {
		List<JsonNode> goPool = keepThreeRuns(results);
		keep(results.resolve("cache-miss"), 1000, 1000, 1000);
		Map<String, List<JsonNode>> history = Map.of("go-pool", goPool, "cache-miss", List.of(run("latest", -0.4)));

		ObjectNode comparison = AgainstEstimatesHeldOut.of(List.of("go-pool", "cache-miss"), history, results)
			.orElseThrow();

		assertThat(comparison.at("/simulator/runs").intValue()).isEqualTo(4);
		assertThat(comparison.at("/simulator/worst")).isEqualTo(JsonText.read("""
				{"experiment": "cache-miss", "dir": "latest"}"""));
	}

	// each experiment one vote, however many runs it has: cache-miss's one run goes
	// to the simulator, -5% against the arithmetic's +10%
	@Test
	void of_twoHeldOutExperiments_eachCountOnceInTheSplit(@TempDir Path results) throws IOException {
		List<JsonNode> goPool = keepThreeRuns(results);
		keep(results.resolve("cache-miss"), 1100, 1000, 1000);
		Map<String, List<JsonNode>> history = Map.of("go-pool", goPool, "cache-miss", List.of(run("latest", -0.05)));

		ObjectNode comparison = AgainstEstimatesHeldOut.of(List.of("go-pool", "cache-miss"), history, results)
			.orElseThrow();

		assertThat(comparison.get("experiments").intValue()).isEqualTo(2);
		assertThat(comparison.at("/estimates/0/simulatorNearer").intValue()).isEqualTo(1);
		assertThat(comparison.at("/estimates/0/estimateNearer").intValue()).isEqualTo(1);
	}

	// only the latest conditions repeat: run-1's protocol was v6
	@Test
	void of_aRunUnderEarlierConditions_isLeftOut(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);
		((ObjectNode) runs.getFirst()).put("protocol", "v6");

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.get("runs").intValue()).isEqualTo(2);
	}

	@Test
	void of_aDisturbedRun_isLeftOut(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);
		((ObjectNode) runs.get(1).get("checks")).putArray("disturbed").add(1200);

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.at("/simulator/runs").intValue()).isEqualTo(2);
		assertThat(comparison.at("/simulator/worst/dir").stringValue()).isEqualTo("latest");
	}

	@Test
	void of_aRunWithoutBaselines_countsForTheSimulatorAlone(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);
		Files.delete(results.resolve("go-pool/run-1/baselines.json"));

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.at("/simulator/runs").intValue()).isEqualTo(3);
		assertThat(comparison.at("/estimates/0/runs").intValue()).isEqualTo(2);
	}

	// on the runs that have both knees, run-2 and the latest, the arithmetic's errors of
	// +24% and -20% have a median size of 22% against the simulator's 25%; over all its
	// runs the simulator's median would be 20%, and the experiment would go to it
	@Test
	void of_anExperiment_isJudgedOnTheRunsThatHaveBothKnees(@TempDir Path results) throws IOException {
		List<JsonNode> runs = keepThreeRuns(results);
		keep(results.resolve("go-pool/run-2"), 1240, 1600, 1700);
		keep(results.resolve("go-pool"), 800, 800, 700);
		Files.delete(results.resolve("go-pool/run-1/baselines.json"));

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.at("/estimates/0/estimateNearer").intValue()).isEqualTo(1);
		assertThat(comparison.at("/estimates/0/simulatorNearer").intValue()).isZero();
	}

	// the simulator's -10% against the arithmetic's +10%
	@Test
	void of_anExperimentWhereBothAreAsNear_countsForNeither(@TempDir Path results) throws IOException {
		keep(results.resolve("go-pool"), 1100, 1000, 1000);

		ObjectNode comparison = AgainstEstimatesHeldOut
			.of(GO_POOL, Map.of("go-pool", List.of(run("latest", -0.1))), results)
			.orElseThrow();

		assertThat(comparison.at("/estimates/0/simulatorNearer").intValue()).isZero();
		assertThat(comparison.at("/estimates/0/estimateNearer").intValue()).isZero();
	}

	// +30% on run-1 and -30% on the latest
	@Test
	void of_twoRunsAsFarOff_nameTheFirstTheWorst(@TempDir Path results) throws IOException {
		List<JsonNode> runs = List.of(run("run-1/", 0.3), run("latest", -0.3));

		ObjectNode comparison = AgainstEstimatesHeldOut.of(GO_POOL, Map.of("go-pool", runs), results).orElseThrow();

		assertThat(comparison.at("/simulator/worst/dir").stringValue()).isEqualTo("run-1/");
		assertThat(comparison.at("/simulator/worstError").doubleValue()).isEqualTo(0.3);
	}

	@Test
	void of_noBaselinesAtAll_leaveTheEstimatesWithoutFigures(@TempDir Path results) {
		ObjectNode comparison = AgainstEstimatesHeldOut
			.of(GO_POOL, Map.of("go-pool", List.of(run("latest", 0.1))), results)
			.orElseThrow();

		assertThat(comparison.at("/estimates/0")).isEqualTo(JsonText.read("""
				{"estimate": "bottleneckArithmetic", "name": "Bottleneck arithmetic", "runs": 0,
				 "medianAbsError": null, "worstError": null, "worst": null,
				 "simulatorNearer": 0, "estimateNearer": 0, "verdict": "level",
				 "text": {"medianAbsError": "-", "worstError": "-"}}"""));
	}

	// a two-sided sign test at 5%: no split of five experiments gets there, 5 to 0
	// happening 6.3% of the time; 6 to 0 happens 3.1%, 8 to 1 3.9% and 7 to 2 18%
	@ParameterizedTest(name = "simulator nearer in {0}, the estimate in {1}: {2}")
	@CsvSource({ "3, 2, level", "5, 0, level", "6, 0, simulator nearer", "0, 6, estimate nearer", "7, 2, level",
			"8, 1, simulator nearer", "0, 0, level" })
	void verdict_theExperimentsEachWasNearerIn_areJudgedByASignTest(int simulatorNearer, int estimateNearer,
			String verdict) {
		assertThat(AgainstEstimatesHeldOut.verdict(simulatorNearer, estimateNearer)).isEqualTo(verdict);
	}

	// go-pool's three runs: the simulator's errors +10%, -30% and +20%; the arithmetic's
	// knees 1,150, 1,500 and 900 req/s
	private static List<JsonNode> keepThreeRuns(Path results) throws IOException {
		keep(results.resolve("go-pool/run-1"), 1150, 1200, 1300);
		keep(results.resolve("go-pool/run-2"), 1500, 1600, 1700);
		keep(results.resolve("go-pool"), 900, 800, 700);
		return List.of(run("run-1/", 0.1), run("run-2/", -0.3), run("latest", 0.2));
	}

	// a run's baselines.json, with each estimate's knee in the file's order
	private static void keep(Path run, int arithmetic, int estimate, int extrapolation) throws IOException {
		Files.createDirectories(run);
		Files.writeString(run.resolve("baselines.json"), """
				{"baselines": {"bottleneckArithmetic": {"kneeRps": %d}, "analyticEstimate": {"kneeRps": %d},
				 "utilisationExtrapolation": {"kneeRps": %d}}}""".formatted(arithmetic, estimate, extrapolation));
	}

	// a counted run under protocol v7, as the run history records it
	private static ObjectNode run(String dir, double kneeError) {
		return JsonText.object("""
				{"dir": "%s", "protocol": "v7", "machine": "laptop", "os": "Windows-10.0.26300",
				 "powerMode": "Best performance", "engineVersion": "1.6.0", "measuredKnee": {"kneeRps": 1000},
				 "kneeError": %s, "checks": {"disturbed": []}}""".formatted(dir, kneeError));
	}

}
