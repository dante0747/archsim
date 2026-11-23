package io.archsim.benchmarks.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.KeptRun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The tier criteria on the committed results: the second held-out campaign of 21-23 Nov
 * 2026, three runs of all fourteen experiments under protocol v7 and the frozen engine
 * 1.6.0, in which Kafka's third run was disturbed.
 */
class CommittedCriteriaTests {

	private static final Map<String, JsonNode> CRITERIA = criteria();

	@Test
	void criteria_theCommittedResults_missTheP95AtTheseSteps() {
		Map<String, List<Integer>> misses = new LinkedHashMap<>();
		CRITERIA.forEach((experiment, criteria) -> misses.put(experiment,
				criteria.at("/p95/misses").valueStream().map(JsonNode::intValue).toList()));

		assertThat(misses).containsExactly(Map.entry("python-cores", List.of(1631)),
				Map.entry("python-wide", List.of(1862, 2048, 2328)), Map.entry("java-hot-row", List.of(871, 968)),
				Map.entry("go-pool", List.of()), Map.entry("cache-miss", List.of(4180)),
				Map.entry("java-mixed", List.of(4590, 5100)), Map.entry("go-mixed", List.of(5535)),
				Map.entry("python-mixed", List.of()), Map.entry("chain", List.of(2569, 3082)),
				Map.entry("cache-ttl", List.of(5200, 5720)), Map.entry("pg-mixed", List.of(3400, 3740, 4250)),
				Map.entry("pg-hot-row", List.of(940)), Map.entry("cache-aside", List.of(5450, 5995)),
				Map.entry("kafka", List.of(1772, 2126)));
	}

	@Test
	void criteria_theCommittedResults_countTheRunsAlikeLeavingTheDisturbedOut() {
		Map<String, List<Integer>> runs = new LinkedHashMap<>();
		CRITERIA.forEach((experiment, criteria) -> runs.put(experiment,
				List.of(criteria.at("/knee/runs").intValue(), criteria.at("/knee/disturbedRuns").intValue())));

		assertThat(runs).containsExactly(Map.entry("python-cores", List.of(3, 0)),
				Map.entry("python-wide", List.of(3, 0)), Map.entry("java-hot-row", List.of(3, 0)),
				Map.entry("go-pool", List.of(3, 0)), Map.entry("cache-miss", List.of(3, 0)),
				Map.entry("java-mixed", List.of(3, 0)), Map.entry("go-mixed", List.of(3, 0)),
				Map.entry("python-mixed", List.of(3, 0)), Map.entry("chain", List.of(3, 0)),
				Map.entry("cache-ttl", List.of(3, 0)), Map.entry("pg-mixed", List.of(3, 0)),
				Map.entry("pg-hot-row", List.of(3, 0)), Map.entry("cache-aside", List.of(3, 0)),
				Map.entry("kafka", List.of(2, 1)));
	}

	@Test
	void criteria_theCommittedResults_judgeTheseMedianKneeErrors() {
		Map<String, Double> errors = Map.ofEntries(Map.entry("python-cores", -0.0009),
				Map.entry("python-wide", -0.2455), Map.entry("java-hot-row", -0.0330), Map.entry("go-pool", 0.1515),
				Map.entry("cache-miss", -0.1888), Map.entry("java-mixed", -0.0780), Map.entry("go-mixed", -0.0370),
				Map.entry("python-mixed", 0.1100), Map.entry("chain", -0.0008), Map.entry("cache-ttl", -0.1773),
				Map.entry("pg-mixed", 0.1090), Map.entry("pg-hot-row", 0.0398), Map.entry("cache-aside", -0.1823),
				Map.entry("kafka", -0.1785));

		assertThat(CRITERIA).containsOnlyKeys(errors.keySet())
			.allSatisfy((experiment, criteria) -> assertThat(criteria.at("/knee/error").doubleValue())
				.isCloseTo(errors.get(experiment), within(1e-4)));
	}

	// Kafka's disturbed third run leaves it two counted runs, and the criterion needs
	// three
	@Test
	void criteria_theCommittedResults_meetCriterionOneForAllButKafka() {
		assertThat(CRITERIA.keySet()).filteredOn((experiment) -> !passing("knee").contains(experiment))
			.containsExactly("kafka");
	}

	@Test
	void criteria_theCommittedResults_meetCriterionTwoForGoPoolAlone() {
		assertThat(passing("p95")).containsExactly("go-pool");
	}

	@Test
	void criteria_theCommittedResults_meetCriterionThreeAForSix() {
		assertThat(passing("stable")).containsExactly("go-pool", "cache-miss", "python-mixed", "cache-ttl",
				"pg-hot-row", "cache-aside");
	}

	// every run under v7 was on the same laptop: a second machine is needed
	@Test
	void criteria_theCommittedResults_areNotReproducedOnASecondMachine() {
		assertThat(CRITERIA.values()).allMatch((criteria) -> criteria.at("/reproduced/machines").intValue() == 1)
			.noneMatch((criteria) -> criteria.at("/reproduced/pass").booleanValue())
			.noneMatch((criteria) -> criteria.get("all").booleanValue());
	}

	@Test
	void criteria_theCommittedResults_lookAtThreeRunsOfEachExperiment() {
		assertThat(CRITERIA.values()).allMatch((criteria) -> criteria.at("/reproduced/runs").intValue() == 3);
	}

	@Test
	void stable_javaHotRow_isUnstableAtItsHighestStepBelowTheKnee() {
		assertThat(Objects.requireNonNull(CRITERIA.get("java-hot-row")).at("/stable/unstable"))
			.isEqualTo(Runs.rates(968));
	}

	private static List<String> passing(String criterion) {
		return CRITERIA.entrySet()
			.stream()
			.filter((entry) -> entry.getValue().get(criterion).get("pass").booleanValue())
			.map(Map.Entry::getKey)
			.toList();
	}

	private static Map<String, JsonNode> criteria() {
		RepositoryLayout layout = RepositoryLayout.current();
		Map<String, List<JsonNode>> history = RunHistory.of(layout.results());
		Map<String, JsonNode> criteria = new LinkedHashMap<>();
		for (Experiment experiment : Runs.measured(layout.results())) {
			JsonNode comparison = LatestRuns.load(layout.results(), experiment.id(), KeptRun.COMPARISON).orElseThrow();
			criteria.put(experiment.id(), Tiers.criteria(comparison, history.getOrDefault(experiment.id(), List.of())));
		}
		return criteria;
	}

}
