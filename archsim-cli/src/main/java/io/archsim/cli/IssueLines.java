package io.archsim.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import io.archsim.model.issue.Issue;

/**
 * A design's issues as the Python engine's CLI printed them ({@code _print_issues}): each
 * its severity, code, part and words, and the hint that follows.
 */
final class IssueLines {

	private IssueLines() {
	}

	/**
	 * The lines of a design's issues.
	 * @param issues what validating it found
	 * @return each line, in order
	 */
	static List<String> of(List<Issue> issues) {
		List<String> lines = new ArrayList<>();
		for (Issue issue : issues) {
			// the node, else the edge, an empty one counting as none
			String part = present(issue.nodeId()) ? issue.nodeId() : issue.edgeId();
			String where = present(part) ? " [%s]".formatted(part) : "";
			// the severity padded to the longest one's width
			String severity = "%-7s".formatted(issue.severity().json().toUpperCase(Locale.ROOT));
			lines.add("  " + severity + " " + issue.code() + where + ": " + issue.message());
			if (present(issue.hint())) {
				lines.add("          hint: " + issue.hint());
			}
		}
		return lines;
	}

	private static boolean present(@Nullable String text) {
		return text != null && !text.isEmpty();
	}

}
