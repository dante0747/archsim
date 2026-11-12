package io.archsim.engine.simulator;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's scenarios against loose, documented expectations: they pin the
 * simulation's semantics, not its exact numbers, and they are not predictions of any real
 * system. Auto-sizing is off, so no CPU queues sit on top of the worker pools, and the
 * run is shortened to 40 s after 8 s of warm-up. The frozen Python engine's reference
 * benchmark held it to the same ranges. The three runs are made once, for every test.
 */
@TestInstance(Lifecycle.PER_CLASS)
class StarterReferenceTests {

	private static final String BASELINE = "baseline";

	private static final String ONE_REPLICA = "one-api-replica";

	private static final String THREE_REPLICAS = "three-api-replicas";

	private final Map<String, JsonNode> reports = simulateTheScenarios();

	private static Map<String, JsonNode> simulateTheScenarios() {
		ObjectNode starter = ReferenceDesigns.example("cache-aside-starter.json");
		((ObjectNode) starter.required("architecture")).put("autoSize", false);
		((ObjectNode) starter.required("workload")).put("durationS", 40).put("warmupS", 8);
		Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());
		return Stream.of(BASELINE, ONE_REPLICA, THREE_REPLICAS)
			.collect(Collectors.toMap(Function.identity(),
					(scenario) -> simulator.run(starter.deepCopy(), RunRequest.of(scenario))));
	}

	private JsonNode report(String scenario) {
		return Objects.requireNonNull(this.reports.get(scenario), scenario);
	}

	private double p95(String scenario) {
		return report(scenario).at("/summary/latencyMs/p95").doubleValue();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "baseline, 380, 420", "one-api-replica, 240, 340", "three-api-replicas, 380, 420" })
	void run_starterScenario_throughputIsInItsRange(String scenario, double low, double high) {
		assertThat(report(scenario).at("/summary/throughputRps").doubleValue()).isBetween(low, high);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "baseline, 0.55, 0.80", "one-api-replica, 0.95, 1.0", "three-api-replicas, 0.35, 0.55" })
	void run_starterScenario_apiUtilizationIsInItsRange(String scenario, double low, double high) {
		assertThat(ReferenceDesigns.summaryOf(report(scenario), "api").required("utilization").doubleValue())
			.isBetween(low, high);
	}

	@Test
	void run_starterBaseline_medianIsInItsRange() {
		assertThat(report(BASELINE).at("/summary/latencyMs/p50").doubleValue()).isBetween(35.0, 65.0);
	}

	@Test
	void run_starterBaseline_isHealthy() {
		assertThat(report(BASELINE).at("/findings/0/kind").stringValue()).isEqualTo("healthy");
	}

	@Test
	void run_starterWithOneApiReplica_refusesRequests() {
		assertThat(ReferenceDesigns.summaryOf(report(ONE_REPLICA), "api").required("rejectedTotal").doubleValue())
			.isPositive();
	}

	@Test
	void run_starterWithOneApiReplica_isMoreThanTwiceAsSlowAtP95() {
		assertThat(p95(ONE_REPLICA)).isGreaterThan(2 * p95(BASELINE));
	}

	@Test
	void run_starterWithOneApiReplica_findsTheApiTheBottleneck() {
		JsonNode first = report(ONE_REPLICA).at("/findings/0");

		assertThat(first.required("kind").stringValue()).isEqualTo("bottleneck");
		assertThat(first.required("nodeId").stringValue()).isEqualTo("api");
	}

	@Test
	void run_starterWithThreeApiReplicas_isNoSlowerThanTheBaseline() {
		assertThat(p95(THREE_REPLICAS)).isLessThanOrEqualTo(p95(BASELINE) * 1.05);
	}

}
