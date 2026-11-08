package io.archsim.engine.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}), simulated
 * in Java, gives the report Python gave, counts and events processed exactly and numbers
 * to a relative 1e-9, an integer where Python wrote one, part by part. The report parts
 * the Java writes so far are compared; the analyses (findings, each part's formulas and
 * status, assumptions, sizing, cost, review, estimate, confidence, digests) come with a
 * later slice.
 */
class SimulationCasesTests {

	/** The report's parts the simulator writes so far. */
	private static final List<String> PARTS = List.of("window", "summary", "timeseries", "edges", "traces", "explain");

	/** What each part's entry holds from the simulation. */
	private static final List<String> NODE_PARTS = List.of("id", "type", "label", "category", "summary", "accounting",
			"timeseries", "config", "behaviour");

	private static final List<String> PROVENANCE_COUNTS = List.of("requestsGenerated", "eventsProcessed", "seed");

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() {
		return cases().map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
			ObjectNode report = SIMULATOR.run(recorded.get("design"));

			assertThat(differences(recorded.get("report"), report)).isEmpty();
		}));
	}

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesIntegersWherePythonDid() {
		return cases().map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
			ObjectNode report = SIMULATOR.run(recorded.get("design"));

			assertThat(kindDifferences(recorded.get("report"), report)).isEmpty();
		}));
	}

	private static List<String> differences(JsonNode expected, JsonNode actual) {
		List<String> differences = new ArrayList<>();
		compared(expected, actual).forEach(
				(part, pair) -> differences.addAll(prefixed(part, JsonDiff.differences(pair.get(0), pair.get(1)))));
		return differences;
	}

	private static List<String> kindDifferences(JsonNode expected, JsonNode actual) {
		List<String> differences = new ArrayList<>();
		compared(expected, actual).forEach((part, pair) -> numberKinds(part, pair.get(0), pair.get(1), differences));
		return differences;
	}

	/**
	 * The parts compared, each the recorded and the Java value, by where they sit.
	 */
	private static Map<String, List<JsonNode>> compared(JsonNode expected, JsonNode actual) {
		Map<String, List<JsonNode>> pairs = new LinkedHashMap<>();
		PARTS.forEach((part) -> pairs.put(part, List.of(expected.get(part), actual.path(part))));
		PROVENANCE_COUNTS.forEach((count) -> pairs.put("provenance." + count,
				List.of(expected.get("provenance").get(count), actual.path("provenance").path(count))));
		JsonNode nodes = expected.get("nodes");
		for (int index = 0; index < nodes.size(); index++) {
			for (String key : NODE_PARTS) {
				pairs.put("nodes." + index + "." + key,
						List.of(nodes.get(index).get(key), actual.path("nodes").path(index).path(key)));
			}
		}
		return pairs;
	}

	// an integer in Python's report is an integer in Java's, and a float a float: the
	// digests hash the text
	private static void numberKinds(String where, JsonNode expected, JsonNode actual, List<String> differences) {
		if (expected.isNumber()) {
			if (expected.isIntegralNumber() != actual.isIntegralNumber()) {
				differences.add(where);
			}
			return;
		}
		if (expected.isArray()) {
			for (int index = 0; index < expected.size(); index++) {
				numberKinds(where + "/" + index, expected.get(index), actual.path(index), differences);
			}
		}
		else if (expected.isObject()) {
			expected.properties()
				.forEach((field) -> numberKinds(where + "/" + field.getKey(), field.getValue(),
						actual.path(field.getKey()), differences));
		}
	}

	private static List<String> prefixed(String part, List<String> differences) {
		return differences.stream().map((difference) -> part + ": " + difference).toList();
	}

	private static Stream<JsonNode> cases() {
		try (InputStream compressed = Objects
			.requireNonNull(SimulationCasesTests.class.getResourceAsStream("cases.json.gz"), "cases.json.gz");
				InputStream json = new GZIPInputStream(compressed)) {
			List<JsonNode> cases = new ArrayList<>();
			JsonMapper.builder().build().readTree(json).get("cases").forEach(cases::add);
			return cases.stream();
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read simulator/cases.json.gz", ex);
		}
	}

}
