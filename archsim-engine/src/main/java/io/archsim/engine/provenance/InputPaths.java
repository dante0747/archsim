package io.archsim.engine.provenance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * Where a document's provenance records an input: {@code nodes/<id>/<setting>},
 * {@code edges/<id>/<setting>} or {@code workload/<field>}.
 */
public final class InputPaths {

	private InputPaths() {
	}

	/**
	 * The path of a part's setting.
	 * @param id the part's id
	 * @param key the setting's JSON name
	 * @return the path
	 */
	public static String node(String id, String key) {
		return "nodes/" + id + "/" + key;
	}

	/**
	 * The path of a link's setting.
	 * @param id the link's id
	 * @param key the setting's JSON name
	 * @return the path
	 */
	public static String edge(String id, String key) {
		return "edges/" + id + "/" + key;
	}

	/**
	 * The path of a workload field.
	 * @param key the field's JSON name
	 * @return the path
	 */
	public static String workload(String key) {
		return "workload/" + key;
	}

	/**
	 * The value a path names in a document's JSON, as the Python engine's
	 * {@code value_at} reads it.
	 * @param document the document's JSON
	 * @param path a path: {@code nodes/<id>/<setting>}, {@code edges/<id>/<setting>} or
	 * {@code workload/<field>}
	 * @return the value; JSON {@code null} when it is not set
	 */
	public static JsonNode valueAt(JsonNode document, String path) {
		int slash = path.indexOf('/');
		String scope = (slash < 0) ? path : path.substring(0, slash);
		String rest = (slash < 0) ? "" : path.substring(slash + 1);
		if ("workload".equals(scope)) {
			return orNull(document.path("workload").path(rest));
		}
		if (!"nodes".equals(scope) && !"edges".equals(scope)) {
			return NullNode.getInstance();
		}
		int last = rest.lastIndexOf('/');
		String id = (last < 0) ? "" : rest.substring(0, last);
		String key = rest.substring(last + 1);
		for (JsonNode item : document.path("architecture").path(scope)) {
			if (id.equals(item.path("id").asString(null))) {
				return orNull(item.path("config").path(key));
			}
		}
		return NullNode.getInstance();
	}

	private static JsonNode orNull(JsonNode value) {
		return value.isMissingNode() ? NullNode.getInstance() : value.deepCopy();
	}

}
