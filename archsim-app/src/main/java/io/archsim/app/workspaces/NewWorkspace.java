package io.archsim.app.workspaces;

import java.time.Instant;

/**
 * A workspace to store, with its owner.
 *
 * @param id its id
 * @param name its name, at most 120 characters
 * @param kind {@code personal}, {@code team} or {@code sandbox}
 * @param ownerId who makes it and owns it
 * @param createdAt when
 */
record NewWorkspace(String id, String name, String kind, String ownerId, Instant createdAt) {

}
