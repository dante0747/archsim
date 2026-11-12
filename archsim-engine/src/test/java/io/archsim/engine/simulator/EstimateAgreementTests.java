package io.archsim.engine.simulator;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * The instant estimate from queueing formulas against full simulations of two templates:
 * its mean and p95 latency within 15% of the simulated ones, the busiest part's
 * utilization within 5 points, and the network's share of the time within 15% of what the
 * traces measured. The p95 is a rough lognormal approximation, so it gets no tighter
 * promise. The frozen Python engine's tests held it to the same tolerances. Each template
 * is simulated once, for every test.
 */
@TestInstance(Lifecycle.PER_CLASS)
class EstimateAgreementTests {

	private static final String QUEUEING_LAB = "queueing-lab.json";

	private static final String STARTER = "cache-aside-starter.json";

	private static final double TOLERANCE_PERCENT = 15;

	private static final double UTILIZATION_POINTS = 0.05;

	private final Map<String, JsonNode> reports = simulateTheTemplates();

	private static Map<String, JsonNode> simulateTheTemplates() {
		Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());
		return Stream.of(QUEUEING_LAB, STARTER)
			.collect(Collectors.toMap(Function.identity(),
					(template) -> simulator.run(ReferenceDesigns.example(template), RunRequest.of(null))));
	}

	private static Map<String, Double> meanMsByLabel(JsonNode rows) {
		Map<String, Double> byLabel = new HashMap<>();
		rows.forEach((row) -> byLabel.put(row.required("label").stringValue(), row.required("meanMs").doubleValue()));
		return byLabel;
	}

	private JsonNode report(String template) {
		return Objects.requireNonNull(this.reports.get(template), template);
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_reportsTheAnalyzersEstimate(String template) {
		DesignAnalyzer analyzer = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped());

		JsonNode analyzed = analyzer.analyze(ReferenceDesigns.example(template), null).toJson();

		assertThat(report(template).required("estimate")).isEqualTo(analyzed.required("estimate"));
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_estimatedMeanIsNearTheSimulated(String template) {
		JsonNode report = report(template);

		assertThat(report.at("/estimate/latencyMs/mean").doubleValue())
			.isCloseTo(report.at("/summary/latencyMs/mean").doubleValue(), withinPercentage(TOLERANCE_PERCENT));
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_estimatedP95IsNearTheSimulated(String template) {
		JsonNode report = report(template);

		assertThat(report.at("/estimate/latencyMs/p95").doubleValue())
			.isCloseTo(report.at("/summary/latencyMs/p95").doubleValue(), withinPercentage(TOLERANCE_PERCENT));
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_estimatedBusiestUtilizationIsNearTheSimulated(String template) {
		JsonNode report = report(template);
		JsonNode busiest = report.at("/estimate/busiest");

		JsonNode simulated = ReferenceDesigns.summaryOf(report, busiest.required("id").stringValue());

		assertThat(busiest.required("utilization").doubleValue())
			.isCloseTo(simulated.required("utilization").doubleValue(), within(UTILIZATION_POINTS));
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_estimatedNetworkTimeIsWhatTheTracesMeasured(String template) {
		JsonNode report = report(template);

		Map<String, Double> simulated = meanMsByLabel(report.at("/explain/latencyBreakdown"));
		Map<String, Double> estimated = meanMsByLabel(report.at("/estimate/breakdown"));

		assertThat(estimated.get("Network")).isCloseTo(simulated.get("Network"), withinPercentage(TOLERANCE_PERCENT));
	}

	@ParameterizedTest
	@ValueSource(strings = { QUEUEING_LAB, STARTER })
	void run_template_estimatedBreakdownAddsUpToTheEstimatedMean(String template) {
		JsonNode estimate = report(template).required("estimate");

		double total = meanMsByLabel(estimate.required("breakdown")).values()
			.stream()
			.mapToDouble(Double::doubleValue)
			.sum();

		assertThat(total).isCloseTo(estimate.at("/latencyMs/mean").doubleValue(), withinPercentage(1));
	}

}
