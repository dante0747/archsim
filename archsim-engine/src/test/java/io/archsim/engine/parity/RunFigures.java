package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the simulation baselines keep of a run ({@code figures} in
 * {@code scripts/export_simulation_baselines.py}, since deleted): its counts and digests,
 * the summary's values and latencies, and each part's summary values, by its id; and the
 * Java's figures of a run read as the frozen engine's. The Java's report is read as the
 * frozen engine's where a deliberate engine change departs ({@link RunDepartures}), its
 * figures kept from the report so read. Its input digest is taken over its design as the
 * frozen engine was given it, as for its golden reports
 * ({@link ReportComparison#recordedInputDigest}), and its result digest over the result
 * so read ({@link ReportComparison#recordedResultDigest}), each once the digest is shown
 * to be its own: a run gives the frozen engine's result digest only where every series,
 * formula and figure of its result is the frozen engine's.
 */
final class RunFigures {

	private static final String PROVENANCE = "provenance";

	private static final String INPUT_DIGEST = "inputDigest";

	private static final String RESULT_DIGEST = "resultDigest";

	private static final List<String> KEPT_PROVENANCE = List.of("eventsProcessed", "requestsGenerated", INPUT_DIGEST,
			RESULT_DIGEST);

	private static final List<String> LATENCIES = List.of("latencyMs", "latencyWithFailuresMs");

	private RunFigures() {
	}

	/**
	 * The Java's figures of a run, read as the frozen engine's.
	 * @param java the Java's report of the run
	 * @param python the baselines' figures of the frozen engine's run of the same seed
	 * @param frozen the frozen engine's report of a run of the same design
	 * ({@link Baselines#frozenReport}), or a missing node where there is none
	 * @return what the baselines would keep of the report so read
	 */
	static ObjectNode of(JsonNode java, JsonNode python, JsonNode frozen) {
		JsonNode read = RunDepartures.asRecorded(java, python, frozen);
		ObjectNode figures = kept(read);
		ReportComparison.recordedInputDigest(java, frozen.path("input"))
			.ifPresent((digest) -> figures.put(INPUT_DIGEST, digest));
		ReportComparison.recordedResultDigest(java, read).ifPresent((digest) -> figures.put(RESULT_DIGEST, digest));
		return figures;
	}

	/**
	 * What the baselines keep of a report, as the script kept it of the frozen engine's.
	 * @param report the run's report
	 * @return its figures
	 */
	static ObjectNode kept(JsonNode report) {
		ObjectNode figures = JsonNodeFactory.instance.objectNode();
		KEPT_PROVENANCE.forEach((key) -> figures.set(key, report.get(PROVENANCE).get(key)));
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
