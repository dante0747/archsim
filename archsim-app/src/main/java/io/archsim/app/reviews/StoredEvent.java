package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A change, as the history stores it.
 *
 * @param id the event's id
 * @param action what changed
 * @param targetKind what kind of thing it changed
 * @param targetId which one
 * @param projectId the project it is in, if any
 * @param actorId who changed it
 * @param actorKind {@code user}, {@code token} or {@code system}
 * @param meta what else was recorded
 * @param ip the address the change came from
 * @param createdAt when
 */
record StoredEvent(long id, String action, String targetKind, @Nullable String targetId, @Nullable String projectId,
		@Nullable String actorId, String actorKind, @Nullable JsonNode meta, @Nullable String ip, Instant createdAt) {

}
