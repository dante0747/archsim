package io.archsim.mcp;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonJson;
import io.archsim.parity.ApiPriceDepartures;

/**
 * The runs of the recorded session whose design prices a third-party API at 0, which
 * depart from the Python server's (ADR-0081, {@link ApiPriceDepartures}): the Java
 * records the price as unset among the run's parts, so the run's result digest, and its
 * id taken from it, are its own; and an API it does not price leaves a cost without a
 * price, so the run's monthly cost is empty where Python's was 0. Each such run's digest,
 * id and monthly cost are learned from the answer that first names them, beside Python's,
 * and every later answer is read with Python's in their place: its names wherever they
 * appear, its monthly cost only in its own summary.
 */
final class RunsPricedAtZero {

	/** The figures a run's summary names it by. */
	private static final Set<String> NAMES = Set.of("resultDigest", "runId");

	private static final String RUN_ID = "runId";

	private static final String COST = "monthlyCostUsd";

	/** The Java's digests and ids, to Python's. */
	private final Map<String, String> names = new LinkedHashMap<>();

	/**
	 * Python's monthly cost of each departing run whose Java cost is empty, by its id.
	 */
	private final Map<String, JsonNode> costs = new HashMap<>();

	/**
	 * Learns the names and monthly cost of a run of a design that prices an API at 0.
	 * @param document the design the request simulated, if any
	 * @param python Python's answer
	 * @param java the Java's answer
	 */
	void learn(JsonNode document, JsonNode python, JsonNode java) {
		if (document.isObject() && ApiPriceDepartures.pricesAnApiAtZero(document)) {
			learnRuns(python.at("/result/structuredContent"), java.at("/result/structuredContent"));
		}
	}

	/**
	 * An answer of the Java's, with Python's names for the runs that depart, and Python's
	 * monthly cost in the summary of a departing run whose own is empty.
	 * @param java the Java's answer, as JSON text
	 * @return the answer as Python would have named and priced its runs
	 */
	JsonNode asPython(String java) {
		String text = java;
		// a digest before the id that is its first characters
		List<Map.Entry<String, String>> longestFirst = this.names.entrySet()
			.stream()
			.sorted(Comparator.comparingInt((Map.Entry<String, String> name) -> name.getKey().length()).reversed())
			.toList();
		for (Map.Entry<String, String> name : longestFirst) {
			text = text.replace(name.getKey(), name.getValue());
		}
		JsonNode answer = McpSession.JSON.readTree(text);
		putCosts(answer.at("/result/structuredContent"));
		for (JsonNode content : answer.at("/result/content")) {
			String json = content.path("text").stringValue("");
			if (json.startsWith("{") && content instanceof ObjectNode written) {
				JsonNode value = McpSession.JSON.readTree(json);
				if (putCosts(value)) {
					written.put("text", PythonJson.writePydantic(value, 2));
				}
			}
		}
		return answer;
	}

	private void learnRuns(JsonNode python, JsonNode java) {
		if (python.isObject() && java.isObject()) {
			learnRun(python, java);
			python.propertyNames().forEach((key) -> learnRuns(python.get(key), java.path(key)));
		}
		else if (python.isArray() && java.isArray()) {
			for (int index = 0; index < Math.min(python.size(), java.size()); index++) {
				learnRuns(python.get(index), java.get(index));
			}
		}
	}

	// a run's summary: its names, and Python's monthly cost where the Java's is empty
	private void learnRun(JsonNode python, JsonNode java) {
		for (String key : NAMES) {
			JsonNode theirs = python.path(key);
			JsonNode ours = java.path(key);
			if (theirs.isString() && ours.isString()) {
				this.names.put(ours.stringValue(), theirs.stringValue());
			}
		}
		JsonNode cost = python.path(COST);
		boolean free = cost.isNumber() && cost.doubleValue() == 0;
		if (python.path(RUN_ID).isString() && java.path(COST).isNull() && free) {
			this.costs.put(python.path(RUN_ID).stringValue(), python.get(COST));
		}
	}

	// puts Python's monthly cost in each summary of a departing run whose own is
	// empty, and says whether it put one
	private boolean putCosts(JsonNode value) {
		boolean put = false;
		if (value instanceof ObjectNode summary) {
			JsonNode cost = this.costs.get(summary.path(RUN_ID).stringValue(""));
			if (cost != null && summary.path(COST).isNull()) {
				summary.set(COST, cost);
				put = true;
			}
		}
		for (JsonNode child : value) {
			put |= putCosts(child);
		}
		return put;
	}

}
