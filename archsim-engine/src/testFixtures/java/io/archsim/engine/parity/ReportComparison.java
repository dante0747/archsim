package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.ReportDigest;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

/**
 * A report of the Java simulator against the frozen engine's report of the same run:
 * counts, events processed and digests exactly, numbers to a relative 1e-9, and an
 * integer where Python wrote one (but for two fields, below). Left out are only what the
 * Java writes as its own (ADR-0050): the engine's and the report's versions, the
 * wall-clock time, and the runtime that ran it; its cost is compared as
 * {@link CostDepartures} compares a cost, its design and parts as the frozen engine would
 * have written them at its schema ({@link SchemaDepartures}), its input and result
 * digests checked by their definitions and then taken over that design and those results
 * (ADR-0074, ADR-0081), and Python's findings as the Java words a count of one request
 * ({@link WordingDepartures}).
 */
public final class ReportComparison {

	/**
	 * The keys of Python's provenance that name its engine's and report's versions,
	 * machine and runtime.
	 */
	private static final Set<String> PYTHON_RUNTIME = Set.of("engineVersion", "reportVersion", "wallSeconds", "python",
			"simpy");

	/**
	 * The keys of Java's provenance that name its engine's and report's versions, machine
	 * and runtime.
	 */
	private static final Set<String> JAVA_RUNTIME = Set.of("engineVersion", "reportVersion", "wallSeconds", "java");

	/**
	 * Where Python writes an integer or a float by the line, and the Java a float: a cost
	 * line's instances (a literal 0 or 1 on a line billed per use or by the hour) and an
	 * item's quantity (the literal 730 hours of a month on a load balancer's or a control
	 * plane's hours). The values must be Python's exactly; the golden files compare them
	 * as numbers (ADR-0020), and no digest covers them.
	 */
	private static final Pattern KIND_BY_LINE = Pattern
		.compile("/cost/byNode/[0-9]+/(instances|items/[0-9]+/quantity)");

	private static final String COST = "cost";

	private static final String INPUT_DIGEST = "inputDigest";

	private static final String RESULT_DIGEST = "resultDigest";

	private ReportComparison() {
	}

	/**
	 * Where the Java report differs from Python's.
	 * @param python the frozen engine's report
	 * @param java the Java simulator's report of the same run
	 * @return each difference, by JSON Pointer; none when the reports agree
	 */
	public static List<String> differences(JsonNode python, JsonNode java) {
		return differences(python, java, CostDepartures.Departing.NONE);
	}

	/**
	 * Where the Java report differs from Python's, where some of its cost lines depart
	 * from Python's.
	 * @param python the frozen engine's report
	 * @param java the Java simulator's report of the same run
	 * @param departing the cost lines that depart
	 * @return each difference, by JSON Pointer; none when the reports agree
	 */
	public static List<String> differences(JsonNode python, JsonNode java, CostDepartures.Departing departing) {
		ObjectNode javaReport = (ObjectNode) SchemaDepartures
			.asRecorded(withRecordedDigests(comparable(java, JAVA_RUNTIME), python), python);
		if (javaReport.has(COST)) {
			javaReport.set(COST, CostDepartures.comparable(python.path(COST), javaReport.get(COST), departing));
		}
		Compared compared = new Compared(comparable(WordingDepartures.findingsAsJavaWordsThem(python), PYTHON_RUNTIME),
				javaReport);
		List<String> differences = new ArrayList<>(JsonDiff.differences(compared.expected(), compared.actual()));
		numberKinds("", compared, differences);
		return differences;
	}

	private static void numberKinds(String where, Compared compared, List<String> differences) {
		JsonNode expected = compared.expected();
		if (expected.isNumber()) {
			numberKind(where, compared, differences);
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

	// an integer in Python's report is an integer in Java's, and a float a float: the
	// digests hash the text; where the kind goes by the line, the value is Python's
	// exactly
	private static void numberKind(String where, Compared compared, List<String> differences) {
		JsonNode expected = compared.expected();
		JsonNode actual = compared.actual();
		if (expected.isIntegralNumber() == actual.isIntegralNumber()) {
			return;
		}
		if (!KIND_BY_LINE.matcher(where).matches()) {
			differences.add(where + " (" + (expected.isIntegralNumber() ? "an integer" : "a float") + " in Python)");
		}
		else if (!actual.isNumber() || expected.doubleValue() != actual.doubleValue()) {
			differences.add(where + " (not exactly Python's " + expected + ")");
		}
	}

	/**
	 * The report with its input digest taken over its design, and its result digest over
	 * its results, as the frozen engine would have written them, once each digest is
	 * checked to be its own.
	 * @param report the Java's report
	 * @param python the frozen engine's report of the same run
	 * @return a copy, its digests as recorded; a digest that is not its own is left as it
	 * is, so the difference shows
	 */
	private static JsonNode withRecordedDigests(JsonNode report, JsonNode python) {
		ObjectNode copy = (ObjectNode) report.deepCopy();
		ObjectNode provenance = (ObjectNode) copy.get("provenance");
		JsonNode input = report.path("input");
		if (input.isObject() && ReportDigest.of(input).equals(provenance.path(INPUT_DIGEST).stringValue(""))) {
			provenance.put(INPUT_DIGEST, ReportDigest.of(SchemaDepartures.asRecorded(input, python.path("input"))));
		}
		if (ReportDigest.ofResult(report).equals(provenance.path(RESULT_DIGEST).stringValue(""))) {
			provenance.put(RESULT_DIGEST, ReportDigest.ofResult(SchemaDepartures.asRecorded(report, python)));
		}
		return copy;
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
