package io.archsim.app.reviews;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.Change;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;

/**
 * Comments written, changed, resolved and deleted, each change with its line in the
 * project's history.
 */
@Component
class CommentRecords {

	private final CommentRepository comments;

	private final AuditLog audit;

	private final Clock clock;

	CommentRecords(CommentRepository comments, AuditLog audit, Clock clock) {
		this.comments = comments;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Stores a new comment and its line in the history, in one transaction.
	 * @param project its project
	 * @param anchor what it is on
	 * @param text its text
	 * @param principal who writes it
	 * @return the comment
	 */
	@Transactional
	StoredComment add(ProjectDesign project, CommentAnchors.Anchor anchor, String text, Principal principal) {
		StoredComment comment = new StoredComment(Ids.newId(), project.workspaceId(), project.id(), anchor.kind(),
				anchor.anchorId(), anchor.runId(), anchor.parentId(), principal.userId(), text, Times.now(this.clock),
				null, null, null);
		this.comments.add(comment);
		Map<String, @Nullable Object> meta = new LinkedHashMap<>();
		meta.put("reply", anchor.parentId() != null);
		meta.put("excerpt", AuditLog.excerpt(text));
		record("comment.create", comment, meta, principal);
		return comment;
	}

	/**
	 * Changes a comment's text.
	 * @param comment the comment
	 * @param text its new text
	 * @param principal its author
	 * @return the comment, changed
	 */
	StoredComment edit(StoredComment comment, String text, Principal principal) {
		Instant now = Times.now(this.clock);
		this.comments.edit(comment.id(), text, now);
		StoredComment edited = comment.edited(text, now);
		record("comment.edit", edited, Map.of("excerpt", AuditLog.excerpt(text)), principal);
		return edited;
	}

	/**
	 * Resolves or reopens a thread.
	 * @param root the thread's first comment
	 * @param resolved {@code true} to resolve it, {@code false} to reopen it
	 * @param principal who does it
	 * @return the first comment, changed
	 */
	StoredComment resolve(StoredComment root, boolean resolved, Principal principal) {
		StoredComment changed = resolved ? root.resolved(Times.now(this.clock), principal.userId())
				: root.resolved(null, null);
		this.comments.resolve(root.id(), changed.resolvedAt(), changed.resolvedBy());
		record(resolved ? "comment.resolve" : "comment.reopen", changed, Map.of(), principal);
		return changed;
	}

	/**
	 * Deletes a comment, and with a thread's first comment its replies.
	 * @param comment the comment
	 * @param byAuthor whether its author deletes it, rather than an owner
	 * @param principal who deletes it
	 */
	void delete(StoredComment comment, boolean byAuthor, Principal principal) {
		Map<String, @Nullable Object> meta = new LinkedHashMap<>();
		meta.put("excerpt", AuditLog.excerpt(comment.body()));
		meta.put("byAuthor", byAuthor);
		record("comment.delete", comment, meta, principal);
		this.comments.delete(comment.id());
	}

	private void record(String action, StoredComment comment, Map<String, @Nullable Object> meta, Principal principal) {
		Map<String, @Nullable Object> details = new LinkedHashMap<>();
		details.put("anchorKind", comment.anchorKind());
		details.put("anchorId", comment.anchorId());
		details.putAll(meta);
		this.audit.record(
				new Change(comment.workspaceId(), comment.projectId(), action, "comment", comment.id(), details),
				principal);
	}

}
