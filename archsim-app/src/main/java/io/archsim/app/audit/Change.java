package io.archsim.app.audit;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A change to record in the history, such as a comment written or a sign-off withdrawn.
 *
 * @param workspaceId the workspace it is in
 * @param projectId the project it is in, if any
 * @param action what changed, such as {@code comment.create}
 * @param targetKind what kind of thing it changed, such as {@code comment}
 * @param targetId which one
 * @param meta what else to record; {@code null} values are left out. The history copies
 * it when it records the change.
 */
public record Change(String workspaceId, @Nullable String projectId, String action, String targetKind,
		@Nullable String targetId, Map<String, @Nullable Object> meta) {

	// No compact constructor copying meta: javac then writes the parameter's @Nullable
	// type
	// argument against the field, and NullAway, reading this class from its class file,
	// refuses every Map<String, @Nullable Object> passed in. AuditLog.record copies meta.

}
