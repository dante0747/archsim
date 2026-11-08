package io.archsim.engine.simulator;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.parity.GoldenFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden files' recorded runs (parity/README.md), simulated in Java: each template's
 * base design and one of its scenarios, the time axis compressed to about 1,500 requests,
 * give the report the frozen engine gave, compared as {@link ReportComparison} compares.
 */
class GoldenRunsTests {

	@TestFactory
	Stream<DynamicTest> run_eachGoldenRun_writesTheReportAsPython() {
		return GoldenFile.templates().stream().map(GoldenFile::read).flatMap(GoldenRunsTests::replays);
	}

	private static Stream<DynamicTest> replays(GoldenFile template) {
		return template.at("/runs")
			.properties()
			.stream()
			.map((run) -> DynamicTest.dynamicTest(template.name() + " " + run.getKey(), () -> {
				JsonNode recorded = run.getValue();

				ObjectNode report = SimulationCases.simulate(recorded.get("document"), recorded.get("scenarioId"));

				assertThat(ReportComparison.differences(recorded.get("report"), report)).isEmpty();
			}));
	}

}
