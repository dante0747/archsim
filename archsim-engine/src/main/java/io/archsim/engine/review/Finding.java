package io.archsim.engine.review;

import java.util.List;

/**
 * A risk the design review found.
 *
 * @param id the rule and the parts and links it is about, unique in a review
 * @param severity how much it matters
 * @param rule the rule that found it
 * @param title what it is, in a line
 * @param detail why it matters, with the design's numbers
 * @param suggestion what to do about it
 * @param nodeIds the parts it is about
 * @param edgeIds the links it is about
 */
public record Finding(String id, Severity severity, ReviewRule rule, String title, String detail, String suggestion,
		List<String> nodeIds, List<String> edgeIds) {

	/**
	 * Copies the lists, so the record does not change.
	 */
	public Finding {
		nodeIds = List.copyOf(nodeIds);
		edgeIds = List.copyOf(edgeIds);
	}

}
