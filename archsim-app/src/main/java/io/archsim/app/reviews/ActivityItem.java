package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A change, as the history shows it.
 *
 * @param id the event's id, in the order changes were made
 * @param action what changed, such as {@code project.update}
 * @param targetKind what it changed: {@code project}, {@code run}, {@code comment} ...
 * @param targetId which one
 * @param projectId the project it is in, if any
 * @param actor who changed it, if they are still here
 * @param actorKind {@code user}, {@code token} or {@code system}
 * @param meta what else was recorded
 * @param ip the address the change came from, shown to workspace owners only
 * @param createdAt when
 */
record ActivityItem(long id, String action, String targetKind, @Nullable String targetId, @Nullable String projectId,
		@Nullable PersonView actor, String actorKind, @Nullable JsonNode meta, @Nullable String ip, Instant createdAt) {

}
