package io.archsim.engine.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole simulations against the frozen engine's: each design of
 * {@code simulator/cases.json.gz} ({@code scripts/export_simulation_cases.py}), simulated
 * in Java with the scenario Python simulated, gives the report Python gave, counts,
 * events processed and digests exactly, numbers to a relative 1e-9, and an integer where
 * Python wrote one (but for one field, below). Left out are only what the Java writes as
 * its own (ADR-0050): the report's version, the wall-clock time, and the runtime that ran
 * it.
 */
class SimulationCasesTests {

	/**
	 * The keys of Python's provenance that name its report's version, machine and
	 * runtime.
	 */
	private static final Set<String> PYTHON_RUNTIME = Set.of("reportVersion", "wallSeconds", "python", "simpy");

	/**
	 * The keys of Java's provenance that name its report's version, machine and runtime.
	 */
	private static final Set<String> JAVA_RUNTIME = Set.of("reportVersion", "wallSeconds", "java");

	/**
	 * Where Python writes an integer or a float by the line, and the Java a float: a cost
	 * line's instances, a literal 0 or 1 on a line billed per use or by the hour. The
	 * values are compared; the golden files compare them as numbers (ADR-0020), and no
	 * digest covers them.
	 */
	private static final Pattern KIND_BY_LINE = Pattern.compile("/cost/byNode/[0-9]+/instances");

	@TestFactory
	Stream<DynamicTest> run_eachRecordedDesign_writesTheReportAsPython() {
		return SimulationCases.recorded()
			.stream()
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				ObjectNode report = SimulationCases.simulate(recorded);

				assertThat(differences(new Compared(comparable(recorded.get("report"), PYTHON_RUNTIME),
						comparable(report, JAVA_RUNTIME))))
					.isEmpty();
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

	private static List<String> differences(Compared compared) {
		List<String> differences = new ArrayList<>(JsonDiff.differences(compared.expected(), compared.actual()));
		numberKinds("", compared, differences);
		return differences;
	}

	// an integer in Python's report is an integer in Java's, and a float a float: the
	// digests hash the text
	private static void numberKinds(String where, Compared compared, List<String> differences) {
		JsonNode expected = compared.expected();
		if (expected.isNumber()) {
			if (expected.isIntegralNumber() != compared.actual().isIntegralNumber()
					&& !KIND_BY_LINE.matcher(where).matches()) {
				differences
					.add(where + " (" + (expected.isIntegralNumber() ? "an integer" : "a float") + " in Python)");
			}
			return;
		}
		if (expected.isArray()) {
			for (int index = 0; index < expected.size(); index++) {
				numberKinds(where + "/" + index, compared.at(index), differences);
			}
		}
		else if (expected.isObject()) {
			expected.propertyNames().forEach((key) -> numberKinds(where + "/" + key, compared.at(key), differences));
		}
	}

	private static JsonNode comparable(JsonNode report, Set<String> keys) {
		ObjectNode copy = (ObjectNode) report.deepCopy();
		copy.remove("reportVersion");
		ObjectNode provenance = (ObjectNode) copy.get("provenance");
		keys.forEach(provenance::remove);
		return copy;
	}

	/**
	 * A value of Python's report beside the same value of Java's.
	 *
	 * @param expected Python's
	 * @param actual Java's, missing where Java wrote none
	 */
	private record Compared(JsonNode expected, JsonNode actual) {

		Compared at(int index) {
			return new Compared(this.expected.get(index), this.actual.path(index));
		}

		Compared at(String key) {
			return new Compared(this.expected.get(key), this.actual.path(key));
		}

	}

}
