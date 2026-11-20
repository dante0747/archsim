package io.archsim.engine.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.GoldenDepartures;
import io.archsim.engine.parity.ReportComparison;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}, since
 * deleted), simulated in Java with the scenario, seed and mode Python simulated, gives
 * the report Python gave, compared as {@link ReportComparison} compares. Where a
 * read-through cache's fills make its commands wait more often, the estimate's figures
 * {@code cache-fills.json} lists are the Java's own (ADR-0092), and where a part runs on
 * its own hyperthread pairs those {@code own-pairs.json} lists (ADR-0091), each read as
 * Python's only where the Java's figure is the listed one ({@code OwnFigures}).
 */
class SimulationCasesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String NAME = "name";

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() throws IOException {
		JsonNode own = ownFigures();
		return SimulationCases.recorded()
			.stream()
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get(NAME).stringValue(), () -> {
				ObjectNode report = SimulationCases.simulate(recorded);

				assertThat(ReportComparison.differences(recorded.get("report"), report,
						GoldenDepartures.departing(recorded.get(NAME).stringValue()),
						own.path(recorded.get(NAME).stringValue())))
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
			.containsExactly("1.6.0", "1.21", "1.21", System.getProperty("java.version"));
		assertThat(provenance.get("wallSeconds").doubleValue()).isNotNegative();
	}

	// the figures each run lists as the Java's own: where a read-through cache fills its
	// misses, and on a part's own pairs
	private static JsonNode ownFigures() throws IOException {
		ObjectNode own = (ObjectNode) cases("cache-fills.json").deepCopy();
		cases("own-pairs.json").properties()
			.forEach((listed) -> figuresOf(own, listed.getKey()).setAll((ObjectNode) listed.getValue()));
		return own;
	}

	// the figures a run lists so far, an empty list for a run not listed yet
	private static ObjectNode figuresOf(ObjectNode own, String name) {
		return (own.get(name) instanceof ObjectNode listed) ? listed : own.putObject(name);
	}

	private static JsonNode cases(String name) throws IOException {
		try (InputStream in = Objects.requireNonNull(SimulationCasesTests.class.getResourceAsStream(name),
				"simulator/" + name)) {
			return JSON.readTree(in).path("cases");
		}
	}

}
