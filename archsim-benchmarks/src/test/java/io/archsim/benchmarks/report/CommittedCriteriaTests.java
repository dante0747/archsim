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
 * The tier criteria on the committed results: the held-out campaign of 3-4 Nov 2026,
 * three runs each under protocol v6 and the frozen engine 0.16.0, and the four tuned-on
 * experiments with a repeat of 4 Nov added to the v6 campaign's three, in which hot-row's
 * and Kafka's first runs were disturbed.
 */
class CommittedCriteriaTests {

	private static final Map<String, JsonNode> CRITERIA = criteria();

	@Test
	void criteria_theCommittedResults_missTheP95AtTheseSteps() {
		Map<String, List<Integer>> misses = new LinkedHashMap<>();
		CRITERIA.forEach((experiment, criteria) -> misses.put(experiment,
				criteria.at("/p95/misses").valueStream().map(JsonNode::intValue).toList()));

		assertThat(misses).containsExactly(Map.entry("java-mixed", List.of()), Map.entry("go-mixed", List.of()),
				Map.entry("python-mixed", List.of(440)), Map.entry("chain", List.of(3338)),
				Map.entry("cache-ttl", List.of()), Map.entry("pg-mixed", List.of(3900)),
				Map.entry("pg-hot-row", List.of(856, 942)), Map.entry("cache-aside", List.of(5650, 6215)),
				Map.entry("kafka", List.of(1566, 1879, 2297)));
	}

	@Test
	void criteria_theCommittedResults_countTheRunsAlikeLeavingTheDisturbedOut() {
		Map<String, List<Integer>> runs = new LinkedHashMap<>();
		CRITERIA.forEach((experiment, criteria) -> runs.put(experiment,
				List.of(criteria.at("/knee/runs").intValue(), criteria.at("/knee/disturbedRuns").intValue())));

		assertThat(runs).containsExactly(Map.entry("java-mixed", List.of(3, 0)), Map.entry("go-mixed", List.of(3, 0)),
				Map.entry("python-mixed", List.of(3, 0)), Map.entry("chain", List.of(3, 0)),
				Map.entry("cache-ttl", List.of(3, 0)), Map.entry("pg-mixed", List.of(6, 0)),
				Map.entry("pg-hot-row", List.of(5, 1)), Map.entry("cache-aside", List.of(6, 0)),
				Map.entry("kafka", List.of(5, 1)));
	}

	@Test
	void criteria_theCommittedResults_judgeTheseMedianKneeErrors() {
		Map<String, Double> errors = Map.of("java-mixed", 0.7002, "go-mixed", 0.6666, "python-mixed", 0.2910, "chain",
				0.3039, "cache-ttl", -0.0582, "pg-mixed", 0.1591, "pg-hot-row", -0.0953, "cache-aside", -0.2253,
				"kafka", -0.0303);

		assertThat(CRITERIA).containsOnlyKeys(errors.keySet())
			.allSatisfy((experiment, criteria) -> assertThat(criteria.at("/knee/error").doubleValue())
				.isCloseTo(errors.get(experiment), within(1e-4)));
	}

	@Test
	void criteria_theCommittedResults_meetCriterionOneForCacheTtlAndTheTunedOnFour() {
		assertThat(passing("knee")).containsExactly("cache-ttl", "pg-mixed", "pg-hot-row", "cache-aside", "kafka");
	}

	// a factor of two either way: java-mixed and go-mixed pass on the
	// two light steps below their early knees, cache-ttl on all five
	@Test
	void criteria_theCommittedResults_meetCriterionTwoForThree() {
		assertThat(passing("p95")).containsExactly("java-mixed", "go-mixed", "cache-ttl");
	}

	@Test
	void criteria_theCommittedResults_meetCriterionThreeAForAllButTwo() {
		assertThat(CRITERIA.keySet()).filteredOn((experiment) -> !passing("stable").contains(experiment))
			.containsExactly("pg-mixed", "pg-hot-row");
	}

	// every run under v6 was on the same laptop: a second machine is needed
	@Test
	void criteria_theCommittedResults_areNotReproducedOnASecondMachine() {
		assertThat(CRITERIA.values()).allMatch((criteria) -> criteria.at("/reproduced/machines").intValue() == 1)
			.noneMatch((criteria) -> criteria.at("/reproduced/pass").booleanValue())
			.noneMatch((criteria) -> criteria.get("all").booleanValue());
	}

	@Test
	void criteria_theCommittedResults_lookAtSixRunsOfTheTunedOnAndThreeOfTheHeldOut() {
		Map<String, Integer> runs = new LinkedHashMap<>();
		CRITERIA.forEach((experiment, criteria) -> runs.put(experiment, criteria.at("/reproduced/runs").intValue()));

		assertThat(runs).containsExactly(Map.entry("java-mixed", 3), Map.entry("go-mixed", 3),
				Map.entry("python-mixed", 3), Map.entry("chain", 3), Map.entry("cache-ttl", 3),
				Map.entry("pg-mixed", 6), Map.entry("pg-hot-row", 6), Map.entry("cache-aside", 6),
				Map.entry("kafka", 6));
	}

	@Test
	void stable_pgHotRow_isUnstableAtItsHighestStepBelowTheKnee() {
		assertThat(Objects.requireNonNull(CRITERIA.get("pg-hot-row")).at("/stable/unstable"))
			.isEqualTo(Runs.rates(942));
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
