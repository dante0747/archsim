package io.archsim.app.support;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * An event in the history, as a test reads it back.
 *
 * @param action what changed
 * @param targetKind what kind of thing it changed
 * @param targetId which one
 * @param meta what else it records
 * @param projectId the project it is in, if any
 */
public record HistoryEvent(String action, String targetKind, @Nullable String targetId, @Nullable JsonNode meta,
		@Nullable String projectId) {

}
