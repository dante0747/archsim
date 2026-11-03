package io.archsim.app.reviews;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * Comments written, changed and deleted: on a part (a node or a link), on the project or
 * on a run, in threads anyone in the workspace can resolve. Every change is recorded in
 * the project's history.
 */
@RestController
class CommentController {

	private final CommentThreads threads;

	private final CommentWriter writer;

	private final CommentEdits edits;

	CommentController(CommentThreads threads, CommentWriter writer, CommentEdits edits) {
		this.threads = threads;
		this.writer = writer;
		this.edits = edits;
	}

	/**
	 * Comment on a part ({@code anchorKind} node or edge, with its id), the project or a
	 * run, or reply to a thread ({@code parentId}: the reply joins that thread's part).
	 * @param principal who is asking
	 * @param projectId the project
	 * @param request the comment
	 * @return {@code 201} with the comment
	 */
	@PostMapping("/projects/{projectId}/comments")
	ResponseEntity<CommentView> create(Principal principal, @PathVariable String projectId,
			@ApiBody CommentCreate request) {
		CommentView created = this.threads.view(this.writer.create(principal, projectId, request), principal);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	/**
	 * Change the text of your own comment.
	 * @param principal who is asking
	 * @param commentId the comment
	 * @param request its new text
	 * @return the comment
	 */
	@PatchMapping("/comments/{commentId}")
	CommentView edit(Principal principal, @PathVariable String commentId, @ApiBody CommentUpdate request) {
		return this.threads.view(this.edits.edit(principal, commentId, request.body()), principal);
	}

	/**
	 * Delete your own comment (owners: any comment). Deleting the first comment of a
	 * thread deletes its replies.
	 * @param principal who is asking
	 * @param commentId the comment
	 * @return {@code 204}
	 */
	@DeleteMapping("/comments/{commentId}")
	ResponseEntity<Void> delete(Principal principal, @PathVariable String commentId) {
		this.edits.delete(principal, commentId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Resolve the thread this comment is in ({@code {"resolved": false}} reopens it).
	 * Anyone in the workspace may. Returns the thread's first comment.
	 * @param principal who is asking
	 * @param commentId the comment
	 * @param request whether to resolve or reopen; no body resolves
	 * @return the thread's first comment
	 */
	@PostMapping("/comments/{commentId}/resolve")
	CommentView resolve(Principal principal, @PathVariable String commentId,
			@ApiBody(required = false) @Nullable CommentResolve request) {
		boolean resolved = request == null || request.resolved();
		return this.threads.view(this.edits.resolve(principal, commentId, resolved), principal);
	}

}
