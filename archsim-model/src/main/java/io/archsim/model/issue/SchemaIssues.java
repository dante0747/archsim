package io.archsim.model.issue;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.codec.BindingError;
import io.archsim.model.text.PythonText;

/**
 * Turns the codec's errors about a document into {@code document.schema} issues, naming
 * the node or link an error is in when the location leads through one:
 * {@code link 'lb-api': Input should be 'read' or 'write' (at architecture.edges.1.config.requestTypes.1)}.
 */
public final class SchemaIssues {

	/**
	 * The parts a location has at least when it leads through a node or link:
	 * {@code architecture}, {@code nodes} or {@code edges}, and the index.
	 */
	private static final int ITEM_LOCATION_PARTS = 3;

	private SchemaIssues() {
	}

	/**
	 * One issue per error, in order.
	 * @param errors what the codec found
	 * @param raw the JSON that was read, to find node and edge ids by their index
	 * @param prefix put before each error's location in the message and the path
	 * @return the issues, all errors
	 */
	public static List<Issue> of(List<BindingError> errors, @Nullable JsonNode raw, String prefix) {
		return errors.stream().map((error) -> issue(error, raw, prefix)).toList();
	}

	private static Issue issue(BindingError error, @Nullable JsonNode raw, String prefix) {
		List<Object> parts = error.location().parts();
		JsonNode id = itemId(parts, raw);
		String nodeId = idIn("nodes", parts, id);
		String edgeId = idIn("edges", parts, id);
		String path = error.location().toString();
		// Python names the node or link only when its id is truthy
		boolean named = id != null && PythonText.truthy(id);
		String where = named ? name(nodeId, edgeId) : path;
		return Issue.error("document.schema", "%s: %s (at %s%s)".formatted(where, error.message(), prefix, path))
			.atNode(nodeId)
			.atEdge(edgeId)
			.atPath(prefix + path);
	}

	/**
	 * The id of the node or link a location leads through, as the raw JSON has it.
	 * @param parts the location's parts, such as {@code architecture.nodes.2.config}
	 * @param raw the JSON that was read
	 * @return the item's {@code id}, or {@code null} when the location leads through no
	 * item, or the item is not an object or has no id
	 */
	private static @Nullable JsonNode itemId(List<Object> parts, @Nullable JsonNode raw) {
		if (raw == null || !raw.isObject() || parts.size() < ITEM_LOCATION_PARTS
				|| !"architecture".equals(parts.get(0))) {
			return null;
		}
		JsonNode items = raw.path("architecture").path(String.valueOf(parts.get(1)));
		if (!(parts.get(2) instanceof Integer index) || !items.isArray() || index >= items.size()) {
			return null;
		}
		JsonNode item = items.get(index);
		return item.isObject() ? item.get("id") : null;
	}

	/**
	 * An item's id as text, when the location leads through the given list.
	 * @param list {@code nodes} or {@code edges}
	 * @param parts the location's parts
	 * @param id the item's id, if the location leads through an item that has one
	 * @return the id's text, or {@code null} when the item is in another list or its id
	 * is missing or {@code null}
	 */
	private static @Nullable String idIn(String list, List<Object> parts, @Nullable JsonNode id) {
		if (id == null || id.isNull() || !list.equals(parts.get(1))) {
			return null;
		}
		return PythonText.str(id);
	}

	private static String name(@Nullable String nodeId, @Nullable String edgeId) {
		return (nodeId != null) ? "node '%s'".formatted(nodeId) : "link '%s'".formatted(edgeId);
	}

}
