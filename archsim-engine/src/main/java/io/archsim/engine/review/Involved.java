package io.archsim.engine.review;

import java.util.List;

/**
 * The parts and links a finding is about.
 *
 * @param nodeIds the parts, by id
 * @param edgeIds the links, by id
 */
record Involved(List<String> nodeIds, List<String> edgeIds) {

	/**
	 * Copies the lists, so the record does not change.
	 */
	Involved {
		nodeIds = List.copyOf(nodeIds);
		edgeIds = List.copyOf(edgeIds);
	}

	/**
	 * A finding about one part, and no link.
	 * @param nodeId the part
	 * @return what it is about
	 */
	static Involved node(String nodeId) {
		return new Involved(List.of(nodeId), List.of());
	}

}
