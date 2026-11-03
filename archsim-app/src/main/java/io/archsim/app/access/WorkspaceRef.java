package io.archsim.app.access;

/**
 * A workspace, as access decisions need it.
 *
 * @param id the workspace's id
 * @param kind {@code personal}, {@code team} or {@code sandbox}, which sets its limits
 */
public record WorkspaceRef(String id, String kind) {

}
