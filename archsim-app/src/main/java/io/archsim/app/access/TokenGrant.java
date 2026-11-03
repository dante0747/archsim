package io.archsim.app.access;

import org.jspecify.annotations.Nullable;

/**
 * What a personal API token allows: its user's role in each workspace, lowered to viewer
 * for a {@code read} token, and only in its workspace when it names one.
 *
 * @param workspaceId the one workspace it is limited to, if any
 * @param readOnly whether it is a {@code read} token
 */
public record TokenGrant(@Nullable String workspaceId, boolean readOnly) {

}
