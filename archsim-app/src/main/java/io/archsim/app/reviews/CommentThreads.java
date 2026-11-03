package io.archsim.app.reviews;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;

/**
 * A project's comments, as the asker sees them: who wrote them, whether the part or run
 * they are about is gone from the saved design, and what the asker may do with them.
 */
@Service
class CommentThreads {

	private final ProjectService projects;

	private final CommentRepository comments;

	private final PeopleRepository people;

	CommentThreads(ProjectService projects, CommentRepository comments, PeopleRepository people) {
		this.projects = projects;
		this.comments = comments;
		this.people = people;
	}

	/**
	 * A project's comments, optionally only those on one part, run or the project.
	 * @param principal who is asking
	 * @param projectId the project
	 * @param filter which comments
	 * @return the comments, oldest first
	 */
	List<CommentView> list(Principal principal, String projectId, CommentFilter filter) {
		ProjectDesign project = this.projects.design(principal, projectId, WorkspaceRole.VIEWER);
		return views(this.comments.of(projectId, filter), project, principal);
	}

	/**
	 * One comment as the asker sees it.
	 * @param comment the comment, with its project
	 * @param principal who is asking
	 * @return the comment
	 */
	CommentView view(ProjectComment comment, Principal principal) {
		return views(List.of(comment.comment()), comment.project(), principal).getFirst();
	}

	private List<CommentView> views(List<StoredComment> stored, ProjectDesign project, Principal principal) {
		People who = this.people
			.named(stored.stream().flatMap((comment) -> Stream.of(comment.authorId(), comment.resolvedBy())).toList());
		Parts parts = Parts.of(project.document());
		boolean canWrite = !principal.readOnly();
		return stored.stream().map((comment) -> {
			boolean author = principal.userId().equals(comment.authorId());
			return new CommentView(comment.id(), comment.projectId(), comment.anchorKind(), comment.anchorId(),
					comment.runId(), comment.parentId(), who.of(comment.authorId()), comment.body(),
					comment.createdAt(), comment.editedAt(), comment.resolvedAt(), who.of(comment.resolvedBy()),
					parts.detached(comment), canWrite && author,
					canWrite && (author || project.role() == WorkspaceRole.OWNER));
		}).toList();
	}

	/**
	 * The node and edge ids of a saved design.
	 *
	 * @param nodes the nodes' ids
	 * @param edges the edges' ids
	 */
	record Parts(Set<String> nodes, Set<String> edges) {

		static Parts of(JsonNode document) {
			return new Parts(ids(document.path("architecture").path("nodes")),
					ids(document.path("architecture").path("edges")));
		}

		/**
		 * The ids of a design's nodes or edges, as Python's {@code str(n.get("id"))}
		 * reads them: a part without one counts as {@code None}.
		 * @param parts the nodes or the edges
		 * @return their ids
		 */
		private static Set<String> ids(JsonNode parts) {
			Set<String> ids = new HashSet<>();
			parts.forEach((part) -> {
				if (part.isObject()) {
					ids.add(part.path("id").asString("None"));
				}
			});
			return ids;
		}

		/**
		 * Whether the part or run a comment is about is gone.
		 * @param comment the comment
		 * @return {@code true} when it is
		 */
		boolean detached(StoredComment comment) {
			return switch (comment.anchorKind()) {
				case "node" -> !this.nodes.contains(comment.anchorId());
				case "edge" -> !this.edges.contains(comment.anchorId());
				case "run" -> comment.runId() == null;
				default -> false;
			};
		}

	}

}
