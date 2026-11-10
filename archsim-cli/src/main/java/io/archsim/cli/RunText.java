package io.archsim.cli;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * What {@code archsim run} prints, in the Python engine's CLI's words: a run's summary, a
 * quick check's note, how far to trust the run, its findings and its digests.
 */
final class RunText {

	/** How many characters of a digest the run's last line shows. */
	private static final int DIGEST_CHARACTERS = 12;

	/** A rate or a latency: thousands separated, one decimal. */
	private static final String ONE_DECIMAL = ",.1f";

	private RunText() {
	}

	/**
	 * A run's lines.
	 * @param report the run's report
	 * @return each line, in order
	 */
	static List<String> lines(JsonNode report) {
		List<String> lines = new ArrayList<>();
		lines.add(summary(report.get("summary")));
		JsonNode mode = report.path("simulationMode");
		if (PythonText.truthy(mode)) {
			lines.add("  " + mode.get("note").stringValue());
		}
		trust(report).forEach((line) -> lines.add("  " + line));
		for (JsonNode finding : report.get("findings")) {
			lines.add("  [%s] %s: %s".formatted(finding.get("severity").stringValue(),
					finding.get("title").stringValue(), finding.get("detail").stringValue()));
		}
		JsonNode provenance = report.get("provenance");
		lines.add("  seed=%s inputDigest=%s resultDigest=%s".formatted(PythonText.str(provenance.get("seed")),
				digest(provenance.get("inputDigest")), digest(provenance.get("resultDigest"))));
		return lines;
	}

	// throughput, success and latency, and the p95 counting failures when any failed
	private static String summary(JsonNode summary) {
		JsonNode latency = summary.get("latencyMs");
		String line = "offered %s req/s | throughput %s req/s | success %s | p50 %s ms | p95 %s ms | p99 %s ms"
			.formatted(number(summary.get("offeredRps"), ONE_DECIMAL),
					number(summary.get("throughputRps"), ONE_DECIMAL), number(summary.get("successRate"), ".2%"),
					number(latency.get("p50"), ONE_DECIMAL), number(latency.get("p95"), ONE_DECIMAL),
					number(latency.get("p99"), ONE_DECIMAL));
		JsonNode withFailures = summary.path("latencyWithFailuresMs").path("p95");
		JsonNode success = summary.get("successRate");
		if (withFailures.isNumber() && success.isNumber() && success.doubleValue() < 1) {
			line += " | p95 counting failures %s ms".formatted(number(withFailures, ONE_DECIMAL));
		}
		return line;
	}

	// the run's confidence and how to raise it, then the queueing formulas' estimate
	private static List<String> trust(JsonNode report) {
		List<String> lines = new ArrayList<>();
		JsonNode confidence = report.path("confidence");
		if (PythonText.truthy(confidence)) {
			lines.add(PythonText.capitalize(confidence.get("level").stringValue()) + " confidence: "
					+ confidence.get("reason").stringValue() + ". To raise it: "
					+ confidence.get("howToRaise").stringValue());
		}
		JsonNode estimate = report.path("estimate");
		if (PythonText.truthy(estimate)) {
			lines.add(estimate.get("method").stringValue() + ": " + estimate.get("summary").stringValue());
		}
		return lines;
	}

	private static String number(JsonNode value, String spec) {
		return value.isNumber() ? PythonFormat.format(value.doubleValue(), spec) : "n/a";
	}

	private static String digest(JsonNode value) {
		String digest = value.stringValue();
		return digest.substring(0, Math.min(DIGEST_CHARACTERS, digest.length()));
	}

}
