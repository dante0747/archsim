package io.archsim.model.scenario;

import java.io.Serial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Scenario;
import io.archsim.model.document.ScenarioOverride;
import io.archsim.model.document.TargetKind;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonText;

/**
 * Finding a design's scenarios and applying their overrides, as the Python engine does.
 */
public final class Scenarios {

	/** The config settings whose override resizes a part: its CPU and its memory. */
	private static final Set<String> SIZE_SETTINGS = Set.of("cpuCores", "memoryGib");

	/** The config setting that names the instance type a part runs on. */
	private static final String INSTANCE_TYPE = "instanceType";

	private Scenarios() {
	}

	/**
	 * The scenario {@code id}.
	 * @param document the design whose scenarios to search
	 * @param id the scenario's id
	 * @return the scenario
	 * @throws DocumentInvalidException with a {@code scenario.unknown} issue listing
	 * those there are
	 */
	public static Scenario find(ArchitectureDocument document, String id) {
		return document.scenario(id).orElseThrow(() -> {
			String available = document.scenarios().stream().map(Scenario::id).collect(Collectors.joining(", "));
			return new DocumentInvalidException(List.of(Issue.error("scenario.unknown",
					"Unknown scenario '%s' (available: %s).".formatted(id, available.isEmpty() ? "none" : available))));
		});
	}

	/**
	 * A copy of {@code document} (its JSON) with each override set in turn: in a node's
	 * or an edge's config, or in the workload. A path must lead through existing objects;
	 * only its last key may be new.
	 * @param document the design's JSON, which is not changed
	 * @param overrides the scenario's overrides, in the order to set them
	 * @param scenarioName how the issues name the scenario, such as
	 * {@code Scenario 'Cache down'}
	 * @return the copy, with every override set
	 * @throws DocumentInvalidException with a {@code scenario.invalid_override} issue per
	 * override that targets a missing node, edge or path
	 */
	public static ObjectNode applyOverrides(ObjectNode document, List<ScenarioOverride> overrides,
			String scenarioName) {
		ObjectNode copy = document.deepCopy();
		List<Issue> problems = new ArrayList<>();
		for (ScenarioOverride override : overrides) {
			try {
				apply(copy, override, scenarioName);
			}
			catch (MissingTargetException ex) {
				TargetKind kind = override.target().kind();
				problems.add(Issue.error("scenario.invalid_override", ex.issueMessage())
					.atNode((kind == TargetKind.NODE) ? override.target().id() : null)
					.atEdge((kind == TargetKind.EDGE) ? override.target().id() : null)
					.atPath(override.path())
					.withHint("Edit the scenario so it targets an existing node/edge and field."));
			}
		}
		if (!problems.isEmpty()) {
			throw new DocumentInvalidException(problems);
		}
		return copy;
	}

	private static void apply(ObjectNode document, ScenarioOverride override, String scenarioName) {
		TargetKind kind = override.target().kind();
		if (kind == TargetKind.WORKLOAD) {
			set(document.path("workload"), override, "%s (workload)".formatted(scenarioName));
			return;
		}
		String id = String.valueOf(override.target().id());
		JsonNode items = document.path("architecture").path((kind == TargetKind.NODE) ? "nodes" : "edges");
		ObjectNode item = null;
		for (JsonNode candidate : items) {
			if (candidate instanceof ObjectNode object && id.equals(object.path("id").asString(null))) {
				item = object;
				break;
			}
		}
		if (item == null) {
			throw new MissingTargetException("%s: %s '%s' does not exist".formatted(scenarioName, kind.json(), id));
		}
		if (!item.has("config")) {
			item.putObject("config");
		}
		set(item.get("config"), override, "%s (%s '%s')".formatted(scenarioName, kind.json(), id));
	}

	/**
	 * Sets the override's value at its dotted path inside {@code container}.
	 * @param container the node's or edge's config, or the workload
	 * @param override the override to set
	 * @param where how the issue names what the override targets
	 */
	private static void set(JsonNode container, ScenarioOverride override, String where) {
		String path = override.path();
		String[] parts = path.split("\\.", -1);
		JsonNode current = container;
		for (int i = 0; i < parts.length - 1; i++) {
			JsonNode next = current.isObject() ? current.get(parts[i]) : null;
			if (next == null || !next.isObject()) {
				throw new MissingTargetException(
						"%s: '%s' does not exist (no object at '%s')".formatted(where, path, parts[i]));
			}
			current = next;
		}
		if (!(current instanceof ObjectNode object)) {
			throw new MissingTargetException("%s: cannot set '%s'".formatted(where, path));
		}
		object.set(parts[parts.length - 1], override.value());
	}

	/**
	 * Leaves out the instance type auto-sizing chose for a part a scenario resizes (its
	 * CPU or memory), so the part gets the type that fits its new size. An instance type
	 * the part names itself, or that the scenario sets, is kept.
	 * @param resolved the resolved document's JSON, changed in place
	 * @param document the design as written, before auto-sizing chose any instance type
	 * @param scenario the scenario whose overrides were applied to {@code resolved}
	 */
	public static void dropStaleInstanceTypes(ObjectNode resolved, ArchitectureDocument document, Scenario scenario) {
		Set<String> resized = nodesOverriding(scenario, SIZE_SETTINGS);
		Set<String> named = nodesOverriding(scenario, Set.of(INSTANCE_TYPE));
		for (JsonNode node : resolved.path("architecture").path("nodes")) {
			String id = node.path("id").asString("");
			boolean stale = resized.contains(id) && !named.contains(id) && !namesOwnInstanceType(document, id);
			if (stale && node.get("config") instanceof ObjectNode config) {
				config.remove(INSTANCE_TYPE);
			}
		}
	}

	/**
	 * The nodes a scenario overrides one of these settings of.
	 * @param scenario the scenario whose overrides to look at
	 * @param settings the config paths that count
	 * @return the ids of the nodes whose overrides set one of them
	 */
	private static Set<String> nodesOverriding(Scenario scenario, Set<String> settings) {
		Set<String> ids = new HashSet<>();
		for (ScenarioOverride override : scenario.overrides()) {
			String id = override.target().id();
			if (override.target().kind() == TargetKind.NODE && id != null && settings.contains(override.path())) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Whether the design itself names an instance type for a node.
	 * @param document the design as written
	 * @param id the node's id
	 * @return {@code true} when the node's own config sets one
	 */
	private static boolean namesOwnInstanceType(ArchitectureDocument document, String id) {
		return document.node(id)
			.map((declared) -> PythonText.truthy(declared.config().path(INSTANCE_TYPE)))
			.orElse(false);
	}

	/**
	 * An override that leads nowhere. Its issue message is Python's
	 * {@code str(KeyError(message))} stripped of quotes: the message's repr, with quotes
	 * taken off both ends (a message ending in a quote loses it, as in the Python
	 * engine).
	 */
	private static final class MissingTargetException extends RuntimeException {

		@Serial
		private static final long serialVersionUID = 1L;

		MissingTargetException(String message) {
			super(message);
		}

		String issueMessage() {
			String repr = PythonText.repr(String.valueOf(getMessage()));
			int start = 0;
			int end = repr.length();
			while (start < end && isQuote(repr.charAt(start))) {
				start++;
			}
			while (end > start && isQuote(repr.charAt(end - 1))) {
				end--;
			}
			return repr.substring(start, end);
		}

		private static boolean isQuote(char character) {
			return character == '\'' || character == '"';
		}

	}

}
