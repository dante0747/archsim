package io.archsim.engine.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.archsim.engine.text.PythonStrings;

/**
 * The findings of one review, each once, in the order the Python engine sorts them: by
 * severity, rule and title, the title folded first, as found where all three tie.
 */
final class Findings {

	private static final Comparator<Finding> ORDER = Comparator.comparing(Finding::severity)
		.thenComparing((finding) -> finding.rule().json(), PythonStrings::compare)
		.thenComparing((finding) -> PythonStrings.casefold(finding.title()), PythonStrings::compare)
		.thenComparing(Finding::title, PythonStrings::compare);

	private final List<Finding> found = new ArrayList<>();

	private final Set<String> ids = new HashSet<>();

	/**
	 * Adds a finding, unless the same rule already found it about the same parts and
	 * links.
	 * @param rule the rule that found it
	 * @param severity how much it matters
	 * @param involved the parts and links it is about
	 * @param wording what it says
	 */
	void add(ReviewRule rule, Severity severity, Involved involved, Wording wording) {
		String id = rule.json() + ":" + String.join("+", involved.nodeIds()) + "|"
				+ String.join("+", involved.edgeIds());
		if (this.ids.add(id)) {
			this.found.add(new Finding(id, severity, rule, wording.title(), wording.detail(), wording.suggestion(),
					involved.nodeIds(), involved.edgeIds()));
		}
	}

	/**
	 * The findings, most severe first.
	 * @return the findings, sorted
	 */
	List<Finding> sorted() {
		List<Finding> sorted = new ArrayList<>(this.found);
		sorted.sort(ORDER);
		return List.copyOf(sorted);
	}

}
