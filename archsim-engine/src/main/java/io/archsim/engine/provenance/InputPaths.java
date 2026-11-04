package io.archsim.engine.provenance;

/**
 * Where a document's provenance records an input: {@code nodes/<id>/<setting>},
 * {@code edges/<id>/<setting>} or {@code workload/<field>}.
 */
final class InputPaths {

	private InputPaths() {
	}

	/**
	 * The path of a part's setting.
	 * @param id the part's id
	 * @param key the setting's JSON name
	 * @return the path
	 */
	static String node(String id, String key) {
		return "nodes/" + id + "/" + key;
	}

	/**
	 * The path of a link's setting.
	 * @param id the link's id
	 * @param key the setting's JSON name
	 * @return the path
	 */
	static String edge(String id, String key) {
		return "edges/" + id + "/" + key;
	}

	/**
	 * The path of a workload field.
	 * @param key the field's JSON name
	 * @return the path
	 */
	static String workload(String key) {
		return "workload/" + key;
	}

}
