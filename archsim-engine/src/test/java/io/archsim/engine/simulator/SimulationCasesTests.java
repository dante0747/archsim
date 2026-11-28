package io.archsim.engine.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.GoldenDepartures;
import io.archsim.engine.parity.ReportComparison;
import io.archsim.engine.parity.WordingDepartures;
import io.archsim.engine.report.ReportDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}, since
 * deleted), simulated in Java with the scenario, seed and mode Python simulated, gives
 * the report Python gave, compared as {@link ReportComparison} compares. Where a
 * read-through cache's fills make its commands wait more often, the estimate's figures
 * {@code cache-fills.json} lists are the Java's own (ADR-0092), and where a part runs on
 * its own hyperthread pairs those {@code own-pairs.json} lists (ADR-0091), each read as
 * Python's only where the Java's figure is the listed one ({@code OwnFigures}). Where a
 * deliberate engine change moves every figure a run's requests make, such as evenly
 * spaced arrivals on a trace whose rate changes (engine 1.6.1),
 * {@code departed-runs.json} lists the run, with why, and the parts of its report that
 * are the Java's own: the rest is compared as Python's, and those parts are pinned by
 * their digest.
 */
class SimulationCasesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String NAME = "name";

	/**
	 * The runs whose whole result departs by a deliberate engine change, each with why.
	 */
	private static final String DEPARTED_RUNS = "departed-runs.json";

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() throws IOException {
		JsonNode own = ownFigures();
		JsonNode departed = cases(DEPARTED_RUNS);
		return SimulationCases.recorded()
			.stream()
			.filter((recorded) -> !departed.has(recorded.get(NAME).stringValue()))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get(NAME).stringValue(), () -> {
				ObjectNode report = SimulationCases.simulate(recorded);

				assertThat(ReportComparison.differences(recorded.get("report"), report,
						GoldenDepartures.departing(recorded.get(NAME).stringValue()),
						own.path(recorded.get(NAME).stringValue())))
					.isEmpty();
			}));
	}

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesignWhoseRunDeparts_writesTheRestAsPythonAndItsOwnPartsAsListed()
			throws IOException {
		JsonNode own = ownFigures();
		JsonNode departed = cases(DEPARTED_RUNS);
		return SimulationCases.recorded()
			.stream()
			.filter((recorded) -> departed.has(recorded.get(NAME).stringValue()))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get(NAME).stringValue(),
					() -> assertDepartsAsListed(recorded, departed.get(recorded.get(NAME).stringValue()),
							own.path(recorded.get(NAME).stringValue()))));
	}

	@Test
	void departedRuns_eachListed_isARecordedRun() throws IOException {
		List<String> recorded = SimulationCases.recorded().stream().map((run) -> run.get(NAME).stringValue()).toList();

		assertThat(recorded).containsAll(cases(DEPARTED_RUNS).propertyNames());
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
			.containsExactly("1.6.2", "1.21", "1.21", System.getProperty("java.version"));
		assertThat(provenance.get("wallSeconds").doubleValue()).isNotNegative();
	}

	// a run whose listed parts are the Java's own: the rest of its report, those parts
	// read as Python's (its findings in the Java's words, as they are compared), is
	// Python's report, and the parts are the ones whose digest is listed
	private static void assertDepartsAsListed(JsonNode recorded, JsonNode listed, JsonNode own) {
		JsonNode python = recorded.get("report");
		JsonNode pythonInJavasWords = WordingDepartures.findingsAsJavaWordsThem(python);
		ObjectNode report = SimulationCases.simulate(recorded);
		ObjectNode parts = JSON.createObjectNode();
		ObjectNode readAsPython = report.deepCopy();
		for (JsonNode part : listed.get("parts")) {
			JsonPointer at = JsonPointer.compile(part.stringValue());
			parts.set(part.stringValue(), report.at(at));
			((ObjectNode) readAsPython.at(at.head())).set(at.last().getMatchingProperty(), pythonInJavasWords.at(at));
		}

		assertThat(ReportComparison.differences(python, readAsPython,
				GoldenDepartures.departing(recorded.get(NAME).stringValue()), own))
			.isEmpty();
		assertThat(ReportDigest.of(parts)).isEqualTo(listed.get("digest").stringValue());
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
