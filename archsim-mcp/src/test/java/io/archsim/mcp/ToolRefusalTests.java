package io.archsim.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.issue.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A document's refusal in the Python server's words, as its {@code _fix} wrote them.
 */
class ToolRefusalTests {

	@Test
	void unusable_aPartAndAHint_areNamed() {
		Issue issue = new Issue(Severity.ERROR, "node.bad", "Too few replicas.", "api", null, null, "Raise them.");

		ToolRefusal refusal = ToolRefusal.unusable(new DocumentInvalidException(List.of(issue)));

		assertThat(refusal.getMessage())
			.isEqualTo("The document cannot be used:\nerror node.bad [api]: Too few replicas. Fix: Raise them.");
	}

	@Test
	void unusable_anEmptyPartId_namesTheLink() {
		// as Python's `node_id or edge_id` chose
		Issue issue = new Issue(Severity.ERROR, "edge.bad", "No target.", "", "a-b", null, null);

		ToolRefusal refusal = ToolRefusal.unusable(new DocumentInvalidException(List.of(issue)));

		assertThat(refusal.getMessage()).isEqualTo("The document cannot be used:\nerror edge.bad [a-b]: No target.");
	}

	@Test
	void unusable_emptyIdsAndHint_leaveThemOut() {
		// as Python's `if (node_id or edge_id)` and `if hint` left them out
		Issue issue = new Issue(Severity.WARNING, "doc.odd", "Odd.", "", "", null, "");

		ToolRefusal refusal = ToolRefusal.unusable(new DocumentInvalidException(List.of(issue)));

		assertThat(refusal.getMessage()).isEqualTo("The document cannot be used:\nwarning doc.odd: Odd.");
	}

}
