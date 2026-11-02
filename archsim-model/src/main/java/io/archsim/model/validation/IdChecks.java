package io.archsim.model.validation;

import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;

/**
 * Checks that every node and every link has an id of its own, and records the nodes by id
 * and the link ids for the checks that follow.
 */
final class IdChecks {

	private final ValidationContext context;

	IdChecks(ValidationContext context) {
		this.context = context;
	}

	void check() {
		for (NodeSpec node : this.context.declaredNodes()) {
			if (this.context.hasNode(node.id())) {
				this.context.report(duplicateNodeId(node));
			}
			this.context.index(node);
		}
		for (EdgeSpec edge : this.context.declaredEdges()) {
			if (this.context.hasEdge(edge.id())) {
				this.context.report(duplicateEdgeId(edge));
			}
			this.context.addEdgeId(edge.id());
		}
	}

	private static Issue duplicateNodeId(NodeSpec node) {
		return Issue
			.error("node.duplicate_id", "Two nodes share the id '%s'. Node ids must be unique.".formatted(node.id()))
			.atNode(node.id())
			.withHint("Rename one of the nodes.");
	}

	private static Issue duplicateEdgeId(EdgeSpec edge) {
		return Issue.error("edge.duplicate_id", "Two links share the id '%s'.".formatted(edge.id())).atEdge(edge.id());
	}

}
