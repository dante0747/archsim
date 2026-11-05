package io.archsim.engine.compare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.text.PythonStrings;
import io.archsim.model.text.PythonText;

/**
 * The settings two simulated designs differ in ({@code input_diff}), read as the reports
 * store them (ADR-0040): each design is flattened to paths, a part's settings under
 * {@code node[id]}, a link's under {@code edge[id]} and the workload's under
 * {@code workload}, a list of objects item by item, and the values at each path are
 * compared as Python compares them.
 */
public final class InputDiff {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** A link's ends, each a setting of its own. */
	private static final List<String> ENDS = List.of("source", "target", "sourcePort", "targetPort");

	private InputDiff() {
	}

	/**
	 * The settings two simulated designs differ in.
	 * @param first the first run's simulated design, its report's {@code input}
	 * @param second the second run's
	 * @return the differences, by path in code-point order
	 */
	public static List<InputDifference> between(JsonNode first, JsonNode second) {
		Flat inFirst = Flat.of(first);
		Flat inSecond = Flat.of(second);
		Set<String> paths = new TreeSet<>(PythonStrings::compare);
		paths.addAll(inFirst.paths());
		paths.addAll(inSecond.paths());
		List<InputDifference> differences = new ArrayList<>();
		for (String path : paths) {
			JsonNode before = inFirst.value(path);
			JsonNode after = inSecond.value(path);
			if (!PythonValues.equal(before, after)) {
				String label = inFirst.label(path).or(() -> inSecond.label(path)).orElse(path);
				differences.add(new InputDifference(path, label, before, after));
			}
		}
		return List.copyOf(differences);
	}

	/**
	 * A value nested in objects, and in lists whose first item is an object, by path
	 * ({@code _flatten}): {@code a.b} for a key, {@code a[0]} for a list's item.
	 * @param prefix the value's path; empty for the top
	 * @param value the value
	 * @param out the values found, by path
	 */
	private static void flatten(String prefix, JsonNode value, Map<String, JsonNode> out) {
		if (value.isObject()) {
			value.propertyStream()
				.forEach((property) -> flatten(prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey(),
						property.getValue(), out));
		}
		else if (value.isArray() && !value.isEmpty() && value.get(0).isObject()) {
			for (int i = 0; i < value.size(); i++) {
				flatten("%s[%d]".formatted(prefix, i), value.get(i), out);
			}
		}
		else {
			out.put(prefix, value);
		}
	}

	/**
	 * An object's value under a key, as Python's {@code get(key, {})} reads it: an empty
	 * object when the key is missing.
	 * @param json the object
	 * @param key the key
	 * @return its value, {@code null} included
	 */
	private static JsonNode orEmpty(JsonNode json, String key) {
		return json.has(key) ? json.get(key) : NODES.objectNode();
	}

	/**
	 * An object's value under a key, as Python's {@code get(key)} reads it.
	 * @param json the object
	 * @param key the key
	 * @return its value; JSON {@code null} when the key is missing
	 */
	private static JsonNode stored(JsonNode json, String key) {
		return json.has(key) ? json.get(key) : NODES.nullNode();
	}

	/** A simulated design flattened: each setting's value and words, by path. */
	private static final class Flat {

		private final Map<String, JsonNode> values = new HashMap<>();

		private final Map<String, String> labels = new HashMap<>();

		/**
		 * A design flattened ({@code flatten_input}).
		 * @param input the design, as a report stores it
		 * @return its settings
		 */
		static Flat of(JsonNode input) {
			Flat flat = new Flat();
			JsonNode architecture = input.path("architecture");
			architecture.path("nodes").forEach(flat::addNode);
			architecture.path("edges").forEach(flat::addEdge);
			flat.addAll("workload", "Workload", orEmpty(input, "workload"));
			return flat;
		}

		Set<String> paths() {
			return this.values.keySet();
		}

		JsonNode value(String path) {
			return this.values.getOrDefault(path, NODES.nullNode());
		}

		Optional<String> label(String path) {
			return Optional.ofNullable(this.labels.get(path));
		}

		private void addNode(JsonNode node) {
			String id = PythonText.str(node.path("id"));
			String base = "node[" + id + "]";
			JsonNode label = node.path("label");
			String name = PythonText.truthy(label) ? PythonText.str(label) : id;
			put(base + ".type", name + ": type", stored(node, "type"));
			addAll(base, name, orEmpty(node, "config"));
		}

		private void addEdge(JsonNode edge) {
			String base = "edge[" + PythonText.str(edge.path("id")) + "]";
			String name = "link " + PythonText.str(edge.path("source")) + " → " + PythonText.str(edge.path("target"));
			for (String end : ENDS) {
				put(base + "." + end, name + ": " + end, stored(edge, end));
			}
			addAll(base, name, orEmpty(edge, "config"));
		}

		private void addAll(String base, String name, JsonNode settings) {
			Map<String, JsonNode> flat = new HashMap<>();
			flatten("", settings, flat);
			flat.forEach((key, value) -> put(base + "." + key, name + ": " + key, value));
		}

		private void put(String path, String label, JsonNode value) {
			this.values.put(path, value);
			this.labels.put(path, label);
		}

	}

}
