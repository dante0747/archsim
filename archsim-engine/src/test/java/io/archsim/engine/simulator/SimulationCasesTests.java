package io.archsim.engine.simulator;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.GoldenDepartures;
import io.archsim.engine.parity.ReportComparison;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}, since
 * deleted), simulated in Java with the scenario, seed and mode Python simulated, gives
 * the report Python gave, compared as {@link ReportComparison} compares.
 */
class SimulationCasesTests {

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() {
		return SimulationCases.recorded()
			.stream()
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				ObjectNode report = SimulationCases.simulate(recorded);

				assertThat(ReportComparison.differences(recorded.get("report"), report,
						GoldenDepartures.departing(recorded.get("name").stringValue())))
					.isEmpty();
			}));
	}

	@Test
	void run_aDesign_namesItsEngineAndReportVersionsTheJavaRuntimeAndTheWallClockTime() {
		JsonNode recorded = SimulationCases.recorded().getFirst();

		JsonNode report = SimulationCases.simulate(recorded);

		// pinned, where other tests read EngineVersions: this test says what the versions
		// are
		JsonNode provenance = report.get("provenance");
		assertThat(List.of(provenance.get("engineVersion").stringValue(), report.get("reportVersion").stringValue(),
				provenance.get("reportVersion").stringValue(), provenance.get("java").stringValue()))
			.containsExactly("1.1.1", "1.16", "1.16", System.getProperty("java.version"));
		assertThat(provenance.get("wallSeconds").doubleValue()).isNotNegative();
	}

}
