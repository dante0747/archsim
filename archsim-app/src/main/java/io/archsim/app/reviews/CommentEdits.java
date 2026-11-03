package io.archsim.app.reviews;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.web.ApiException;

/**
 * Changes to comments: their authors edit them, they or an owner delete them, and anyone
 * in the workspace resolves or reopens a thread. A read-only API token does none of this.
 */
@Service
class CommentEdits {

	private final CommentRepository comments;

	private final Access access;

	private final ProjectService projects;

	private final CommentRecords records;

	CommentEdits(CommentRepository comments, Access access, ProjectService projects, CommentRecords records) {
		this.comments = comments;
		this.access = access;
		this.projects = projects;
		this.records = records;
	}

	/**
	 * Changes the text of the asker's own comment.
	 * @param principal who is asking
	 * @param commentId the comment
	 * @param body its new text
	 * @return the comment, with its project
	 * @throws ApiException {@code 403} for a read-only token or someone else's comment,
	 * {@code 404} for a comment the asker may not see, {@code 422} for no text
	 */
	@Transactional
	ProjectComment edit(Principal principal, String commentId, String body) {
		principal.requireWritable();
		ProjectComment found = find(principal, commentId);
		StoredComment comment = found.comment();
		if (!principal.userId().equals(comment.authorId())) {
			throw forbidden("Only its author can edit a comment.");
		}
		String text = CommentText.of(body);
		if (text.equals(comment.body())) {
			return found;
		}
		return new ProjectComment(this.records.edit(comment, text, principal), found.project());
	}

	/**
	 * Deletes a comment: the asker's own, or any for an owner. A thread's first comment
	 * takes its replies with it.
	 * @param principal who is asking
	 * @param commentId the comment
	 * @throws ApiException {@code 403} for a read-only token or someone else's comment
	 * without being an owner, {@code 404} for a comment the asker may not see
	 */
	@Transactional
	void delete(Principal principal, String commentId) {
		principal.requireWritable();
		ProjectComment found = find(principal, commentId);
		boolean author = principal.userId().equals(found.comment().authorId());
		if (!author && found.project().role() != WorkspaceRole.OWNER) {
			throw forbidden("Only its author or a workspace owner can delete a comment.");
		}
		this.records.delete(found.comment(), author, principal);
	}

	/**
	 * Resolves or reopens the thread a comment is in.
	 * @param principal who is asking
	 * @param commentId any comment of the thread
	 * @param resolved {@code true} to resolve it, {@code false} to reopen it
	 * @return the thread's first comment, with its project
	 * @throws ApiException {@code 403} for a read-only token, {@code 404} for a comment
	 * the asker may not see
	 */
	@Transactional
	ProjectComment resolve(Principal principal, String commentId, boolean resolved) {
		principal.requireWritable();
		ProjectComment found = find(principal, commentId);
		String rootId = found.comment().parentId();
		StoredComment root = (rootId != null)
				? this.comments.find(rootId)
					.orElseThrow(() -> new IllegalStateException("no comment " + rootId + ", which a reply refers to"))
				: found.comment();
		if (resolved == (root.resolvedAt() != null)) {
			return new ProjectComment(root, found.project());
		}
		return new ProjectComment(this.records.resolve(root, resolved, principal), found.project());
	}

	private ProjectComment find(Principal principal, String commentId) {
		String what = "Comment '" + commentId + "'";
		StoredComment comment = this.comments.find(commentId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, comment.workspaceId(), WorkspaceRole.VIEWER, what);
		return new ProjectComment(comment, this.projects.design(principal, comment.projectId(), WorkspaceRole.VIEWER));
	}

	private static ApiException forbidden(String message) {
		return new ApiException(HttpStatus.FORBIDDEN, message, List.of());
	}

}
