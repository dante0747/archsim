package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the simulation baselines keep of a run ({@code figures} in
 * {@code scripts/export_simulation_baselines.py}, since deleted): its counts and digests,
 * the summary's values and latencies, and each part's summary values, by its id.
 */
final class RunFigures {

	private static final List<String> PROVENANCE = List.of("eventsProcessed", "requestsGenerated", "inputDigest",
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
