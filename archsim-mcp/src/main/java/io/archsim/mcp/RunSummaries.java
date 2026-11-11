package io.archsim.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.slo.RunFigures;
import io.archsim.engine.slo.SloResult;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Objectives;

/**
 * The compact result an agent needs of a run, as the Python server's {@code summarize}
 * made it: the verdict against the objectives, how far to trust it, the key numbers, the
 * first findings, each part, the cost, the inputs and the run's provenance.
 */
final class RunSummaries {

	/** The characters of a result's digest that are its run's id. */
	static final int RUN_ID_LENGTH = 12;

	/** The findings a summary names, the first of the report's. */
	private static final int FINDINGS = 8;

	private RunSummaries() {
	}

	/**
	 * A run's summary.
	 * @param report the run's report
	 * @param objectives the objectives it is judged against
	 * @return the summary
	 */
	static ObjectNode of(JsonNode report, Objectives objectives) {
		JsonNode summary = report.path("summary");
		SloResult slo = SloResult.evaluate(objectives, RunFigures.read(summary));
		JsonNode provenance = report.path("provenance");
		ObjectNode summarized = JsonNodeFactory.instance.objectNode().put("verdict", slo.text());
		summarized.set("confidence", confidence(report.path("confidence")));
		ArrayNode checks = summarized.putArray("objectives");
		slo.checks().forEach((check) -> checks.add(RecordCodec.write(check)));
		summarized.set("throughputRps", JsonPicks.value(summary.path("throughputRps")));
		summarized.set("offeredRps", JsonPicks.value(summary.path("offeredRps")));
		summarized.set("successRate", JsonPicks.value(summary.path("successRate")));
		summarized.set("latencyMs", JsonPicks.pick(summary.path("latencyMs"), "p50", "p95", "p99"));
		ArrayNode findings = summarized.putArray("findings");
		report.path("findings")
			.valueStream()
			.limit(FINDINGS)
			.forEach((finding) -> findings
				.add(JsonPicks.pick(finding, "severity", "kind", "nodeId", "title", "suggestion")));
		summarized.set("parts", parts(report.path("nodes")));
		summarized.set("monthlyCostUsd", costOf(report.path("cost")));
		summarized.set("inputs", JsonPicks.value(report.at("/assumptions/provenanceCounts")));
		String note = report.at("/simulationMode/note").stringValue("");
		summarized.put("simulationMode", note.isEmpty() ? "full" : note);
		String digest = provenance.path("resultDigest").stringValue("");
		summarized.put("runId", digest.substring(0, Math.min(RUN_ID_LENGTH, digest.length())));
		summarized.put("resultDigest", digest);
		summarized.set("engineVersion", JsonPicks.value(provenance.path("engineVersion")));
		summarized.set("seed", JsonPicks.value(provenance.path("seed")));
		return summarized;
	}

	/**
	 * How far to trust a verdict, as {@code _confidence} cut it down: its level, why,
	 * what would raise it, and what drives it, one line each.
	 * @param confidence the report's or the analysis's confidence
	 * @return the cut-down confidence; {@code null} for none
	 */
	static JsonNode confidence(JsonNode confidence) {
		if (!isGiven(confidence)) {
			return JsonNodeFactory.instance.nullNode();
		}
		ObjectNode cut = JsonPicks.pick(confidence, "level", "reason", "howToRaise");
		ArrayNode drivers = cut.putArray("drivers");
		confidence.path("drivers")
			.forEach((driver) -> drivers.add("%s: %s (%s)".formatted(driver.path("label").asString(),
					driver.path("fieldLabel").asString(), driver.path("kind").asString())));
		return cut;
	}

	/**
	 * The queueing formulas' prediction, as {@code _estimate} cut it down.
	 * @param estimate the analysis's estimate
	 * @return the cut-down estimate; {@code null} for none
	 */
	static JsonNode estimate(JsonNode estimate) {
		if (!isGiven(estimate)) {
			return JsonNodeFactory.instance.nullNode();
		}
		return JsonPicks.pick(estimate, "method", "summary", "latencyMs", "busiest", "overloaded", "saturationRps");
	}

	/**
	 * A month's cost, when the design has one.
	 * @param cost the report's or the analysis's cost
	 * @return the monthly cost; {@code null} when the design has none
	 */
	static JsonNode costOf(JsonNode cost) {
		return cost.path("hasCost").asBoolean() ? JsonPicks.value(cost.path("monthly"))
				: JsonNodeFactory.instance.nullNode();
	}

	private static ArrayNode parts(JsonNode nodes) {
		ArrayNode parts = JsonNodeFactory.instance.arrayNode();
		nodes.forEach((node) -> {
			JsonNode summary = node.path("summary");
			parts.addObject()
				.set("id", JsonPicks.value(node.path("id")))
				.set("label", JsonPicks.value(node.path("label")))
				.set("status", JsonPicks.value(summary.path("status")))
				.set("utilization", JsonPicks.value(summary.path("utilization")))
				.set("p95Ms", JsonPicks.value(summary.path("latencyMs").path("p95")));
		});
		return parts;
	}

	// a Python dict that is there and not empty: `if not c: return None`
	private static boolean isGiven(JsonNode value) {
		return value.isObject() && !value.isEmpty();
	}

}
