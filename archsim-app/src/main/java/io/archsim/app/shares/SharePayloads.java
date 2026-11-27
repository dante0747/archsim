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
 * Telemetry addresses are hidden before anything is stored: a calibration names its
 * source, credentials included when its URL has them, in the design's calibration and in
 * every measured input's provenance record, which the report's assumptions repeat. Other
 * addresses are kept: documented inputs and the report's formulas link the public
 * documentation they come from.
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

	/** The key of a design's calibration, which names its telemetry source. */
	private static final String CALIBRATION = "calibration";

	/**
	 * The kind of a provenance record a calibration writes, naming its telemetry source.
	 */
	private static final String MEASURED = "measured";

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
	 * A copy with the telemetry addresses hidden: every address in a value under a
	 * {@code calibration} and in a provenance record of kind {@code measured}, however
	 * deep: "Prometheus https://user:pass@prom.internal:9090" becomes "Prometheus
	 * (address hidden)". Other addresses are kept: a documented input's link to its
	 * vendor's documentation, a formula's link to its source, the design's own words.
	 * @param value any JSON
	 * @return the copy
	 */
	static JsonNode hideAddresses(JsonNode value) {
		if (value.isArray()) {
			ArrayNode items = NODES.arrayNode();
			value.forEach((item) -> items.add(hideAddresses(item)));
			return items;
		}
		if (!value.isObject()) {
			return value.deepCopy();
		}
		if (MEASURED.equals(value.path("kind").stringValue(""))) {
			return withoutAddresses(value);
		}
		ObjectNode fields = NODES.objectNode();
		for (Map.Entry<String, JsonNode> field : value.properties()) {
			fields.set(field.getKey(), hidden(field.getKey(), field.getValue()));
		}
		return fields;
	}

	// a calibration's value loses every address; any other field is looked through
	private static JsonNode hidden(String key, JsonNode value) {
		if (CALIBRATION.equals(key)) {
			return withoutAddresses(value);
		}
		return hideAddresses(value);
	}

	/**
	 * A copy with every string stripped of addresses, however deep.
	 * @param value any JSON
	 * @return the copy
	 */
	private static JsonNode withoutAddresses(JsonNode value) {
		if (value.isString()) {
			return NODES.stringNode(ADDRESS.matcher(value.stringValue()).replaceAll("(address hidden)"));
		}
		if (value.isArray()) {
			ArrayNode items = NODES.arrayNode();
			value.forEach((item) -> items.add(withoutAddresses(item)));
			return items;
		}
		if (!value.isObject()) {
			return value.deepCopy();
		}
		ObjectNode fields = NODES.objectNode();
		value.properties().forEach((field) -> fields.set(field.getKey(), withoutAddresses(field.getValue())));
		return fields;
	}

}
