package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the simulation baselines keep of a run ({@code figures} in
 * {@code scripts/export_simulation_baselines.py}, since deleted): its counts and digests,
 * the summary's values and latencies, and each part's summary values, by its id. A run's
 * input digest is taken over its design as the frozen engine was given it, as for its
 * golden reports ({@link ReportComparison#recordedInputDigest}): the Java reads that
 * design at a later schema: its version restamped and, from 1.11, pinned to whole cores.
 * The baselines keep no input of the frozen engine's, so the API price of 0 that four
 * templates gave it, which the upgrade to 1.9 unsets (ADR-0081), is not put back: their
 * runs' digests still depart.
 */
final class RunFigures {

	private static final String INPUT_DIGEST = "inputDigest";

	private static final List<String> PROVENANCE = List.of("eventsProcessed", "requestsGenerated", INPUT_DIGEST,
			"resultDigest");

	private static final List<String> LATENCIES = List.of("latencyMs", "latencyWithFailuresMs");

	private RunFigures() {
	}

	/**
	 * A report's figures.
	 * @param report the run's report
	 * @return what the baselines keep of it
	 */
	static ObjectNode of(JsonNode report) {
		ObjectNode figures = JsonNodeFactory.instance.objectNode();
		PROVENANCE.forEach((key) -> figures.set(key, report.get("provenance").get(key)));
		ReportComparison.recordedInputDigest(report, MissingNode.getInstance())
			.ifPresent((digest) -> figures.put(INPUT_DIGEST, digest));
		JsonNode summary = report.get("summary");
		ObjectNode kept = values(summary);
		LATENCIES.stream().filter(summary::has).forEach((key) -> kept.set(key, summary.get(key)));
		figures.set("summary", kept);
		ObjectNode nodes = figures.putObject("nodes");
		report.get("nodes").forEach((node) -> nodes.set(node.get("id").stringValue(), values(node.path("summary"))));
		return figures;
	}

	// a summary's numbers, texts and flags, not its lists, tables or missing values
	private static ObjectNode values(JsonNode table) {
		ObjectNode kept = JsonNodeFactory.instance.objectNode();
		table.properties()
			.stream()
			.filter((entry) -> !entry.getValue().isNull() && !entry.getValue().isContainer())
			.forEach((entry) -> kept.set(entry.getKey(), entry.getValue()));
		return kept;
	}

}
