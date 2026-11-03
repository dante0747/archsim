package io.archsim.app.audit;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * One change, as the {@code audit_events} table holds it.
 *
 * @param workspaceId the workspace the change was made in
 * @param projectId the project it concerns, if any; it has no foreign key, so a project's
 * history outlives the project
 * @param actor who made it
 * @param action what it was, such as {@code project.create}
 * @param targetKind what it was made to: {@code project}, {@code run}, {@code user}...
 * @param targetId which one
 * @param meta what else is worth knowing about it
 * @param at when it was made
 */
record AuditEvent(String workspaceId, @Nullable String projectId, Actor actor, String action, String targetKind,
		@Nullable String targetId, @Nullable ObjectNode meta, Instant at) {

}
