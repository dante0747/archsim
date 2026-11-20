package io.archsim.engine.parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the parity run found, as {@code simulation-parity.json}: how many cases meet the
 * bar, pass each of its tests and replay exactly, the runs that replay exactly, the runs
 * that depart only where the baselines keep too little to read them as the frozen
 * engine's and by which departure, the metric furthest from Python's, the cases that miss
 * and what they missed, and each case.
 */
final class ParityReport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String MISSED = "missed";

	private ParityReport() {
	}

	/**
	 * Writes the report.
	 * @param out the folder to write it to
	 * @param results each case's result
	 * @param threads how many runs ran at once
	 * @param seconds how long the Java runs took
	 * @throws IOException when it cannot be written
	 */
	static void write(Path out, List<CaseResult> results, int threads, double seconds) throws IOException {
		ObjectNode report = totals(results);
		results.stream()
			.flatMap((result) -> result.metrics().stream().map((verdict) -> new Worst(result, verdict)))
			.max(Comparator.comparingDouble(Worst::distance))
			.ifPresent((worst) -> report.set("worst", JSON.valueToTree(worst)));
		ArrayNode missing = report.putArray("casesMissingTheBar");
		results.stream().filter((result) -> !result.meetsTheBar()).forEach((result) -> missing.add(named(result)));
		report.put("threads", threads);
		report.put("javaSeconds", seconds);
		ArrayNode cases = report.putArray("results");
		results.forEach((result) -> cases.add(described(result)));
		Files.createDirectories(out);
		Files.writeString(out.resolve("simulation-parity.json"),
				JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
	}

	private static ObjectNode totals(List<CaseResult> results) {
		ObjectNode totals = JSON.createObjectNode().put("cases", results.size());
		totals.put("casesMeetingTheBar", counted(results, CaseResult::meetsTheBar));
		totals.put("casesInsideTheRange", counted(results, CaseResult::insideRange));
		totals.put("casesWithinTwoPercent", counted(results, CaseResult::withinTwoPercent));
		totals.put("casesWithMismatchedFailures", counted(results, (result) -> !result.failuresMatch()));
		totals.put("casesReplayingExactly", counted(results, CaseResult::replaysExactly));
		totals.put("runs", results.stream().mapToInt(CaseResult::runs).sum());
		totals.put("exactReplays", results.stream().mapToInt(CaseResult::exact).sum());
		totals.put("pythonRunsFailed", results.stream().mapToInt((result) -> result.failures().python()).sum());
		totals.put("javaRunsFailed", results.stream().mapToInt((result) -> result.failures().java()).sum());
		totals.put("digestsEqual", results.stream().mapToInt(CaseResult::digestsEqual).sum());
		totals.put("departingRuns", results.stream().mapToInt((result) -> result.departing().runs()).sum());
		ObjectNode departures = totals.putObject("departures");
		results.stream()
			.flatMap((result) -> result.departing().byDeparture().entrySet().stream())
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum, TreeMap::new))
			.forEach(departures::put);
		return totals;
	}

	private static long counted(List<CaseResult> results, Predicate<CaseResult> test) {
		return results.stream().filter(test).count();
	}

	// a case that missed: which, and what it missed
	private static ObjectNode named(CaseResult result) {
		ObjectNode named = JSON.createObjectNode()
			.put("template", result.template())
			.put("scenario", result.scenario());
		named.set(MISSED, JSON.valueToTree(result.missed()));
		return named;
	}

	// a case with its verdict: whether it meets the bar, and what it missed
	private static ObjectNode described(CaseResult result) {
		ObjectNode described = JSON.valueToTree(result);
		described.put("meetsTheBar", result.meetsTheBar());
		described.set(MISSED, JSON.valueToTree(result.missed()));
		return described;
	}

	/**
	 * A case's metric, of all the cases' the furthest from Python's median.
	 *
	 * @param template the template
	 * @param scenario the scenario, or {@code base}
	 * @param verdict the metric's verdict
	 */
	private record Worst(String template, String scenario, MetricVerdict verdict) {

		Worst(CaseResult result, MetricVerdict verdict) {
			this(result.template(), result.scenario(), verdict);
		}

		// a metric no Java run measured is the furthest
		double distance() {
			Double distance = this.verdict.distance();
			return (distance != null) ? distance : Double.POSITIVE_INFINITY;
		}

	}

}
