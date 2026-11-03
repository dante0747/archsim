package io.archsim.app.shares;

import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.runs.RunResults;

/**
 * What a share link keeps: a frozen copy of one run's results. It holds the report
 * without its request traces (they name every hop of sample requests and make up most of
 * the size), the objectives of the run's design, the project's name, and the design
 * itself (so "Open a copy" can make a project from it; the public answer leaves it out).
 * Addresses of telemetry sources (a calibration's Prometheus URL) are hidden before
 * anything is stored.
 */
final class SharePayloads {

	/** The version of the stored snapshot's shape, apart from the report's own. */
	static final int VERSION = 1;

	/**
	 * An address, as a calibration's source names it: {@code scheme://...}. Its word
	 * boundary and its non-spaces are ASCII ones, so an address right after a letter of
	 * another script is hidden too; Python's Unicode ones kept it.
	 */
	private static final Pattern ADDRESS = Pattern.compile("\\b[a-z][a-z0-9+.-]*://\\S+", Pattern.CASE_INSENSITIVE);

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private SharePayloads() {
	}

	/**
	 * The snapshot of a finished run.
	 * @param projectName its project's name
	 * @param run the run, with the design it simulated
	 * @param report its report
	 * @return the snapshot to store
	 */
	static ObjectNode of(String projectName, RunResults run, JsonNode report) {
		JsonNode document = run.inputDocument();
		ObjectNode payload = NODES.objectNode();
		payload.put("projectName", projectName);
		payload.put("runLabel", run.facts().label());
		payload.put("scenarioName", run.facts().scenarioName());
		JsonNode objectives = document.path("objectives");
		payload.set("objectives", objectives.isObject() ? objectives.deepCopy() : NODES.objectNode());
		payload.set("report", publicReport(report));
		payload.set("document", hideAddresses(document));
		return payload;
	}

	/**
	 * A report as shared: no request traces, no telemetry addresses.
	 * @param report the report
	 * @return a copy to share
	 */
	static JsonNode publicReport(JsonNode report) {
		ObjectNode shared = NODES.objectNode();
		for (Map.Entry<String, JsonNode> field : report.properties()) {
			if (!"traces".equals(field.getKey())) {
				shared.set(field.getKey(), field.getValue());
			}
		}
		return hideAddresses(shared);
	}

	/**
	 * A copy with every calibration's {@code source} stripped of addresses: "Prometheus
	 * https://user:pass@prom.internal:9090" becomes "Prometheus (address hidden)".
	 * @param value any JSON
	 * @return the copy
	 */
	static JsonNode hideAddresses(JsonNode value) {
		if (value.isArray()) {
			ArrayNode copy = NODES.arrayNode();
			value.forEach((item) -> copy.add(hideAddresses(item)));
			return copy;
		}
		if (!value.isObject()) {
			return value.deepCopy();
		}
		ObjectNode copy = NODES.objectNode();
		for (Map.Entry<String, JsonNode> field : value.properties()) {
			copy.set(field.getKey(), hidden(field.getKey(), field.getValue()));
		}
		return copy;
	}

	private static JsonNode hidden(String key, JsonNode value) {
		JsonNode source = value.path("source");
		if ("calibration".equals(key) && value.isObject() && source.isString()) {
			ObjectNode calibration = ((ObjectNode) value).deepCopy();
			calibration.put("source", ADDRESS.matcher(source.stringValue()).replaceAll("(address hidden)"));
			return hideAddresses(calibration);
		}
		return hideAddresses(value);
	}

}
