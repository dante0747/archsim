package io.archsim.engine.simulator;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}), simulated
 * in Java with the scenario Python simulated, gives the report Python gave, compared as
 * {@link ReportComparison} compares.
 */
class SimulationCasesTests {

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() {
		return SimulationCases.recorded()
			.stream()
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				ObjectNode report = SimulationCases.simulate(recorded);

				assertThat(ReportComparison.differences(recorded.get("report"), report)).isEmpty();
			}));
	}

	@Test
	void run_aDesign_namesItsReportVersionTheJavaRuntimeAndTheWallClockTime() {
		JsonNode recorded = SimulationCases.recorded().getFirst();

		JsonNode report = SimulationCases.simulate(recorded);

		JsonNode provenance = report.get("provenance");
		assertThat(List.of(report.get("reportVersion").stringValue(), provenance.get("reportVersion").stringValue(),
				provenance.get("java").stringValue()))
			.containsExactly("1.15", "1.15", System.getProperty("java.version"));
		assertThat(provenance.get("wallSeconds").doubleValue()).isNotNegative();
	}

}
