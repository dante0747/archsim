package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * An invitation to a workspace, without its token.
 *
 * @param id the invitation's id
 * @param workspaceId the workspace
 * @param role the role it gives
 * @param email the verified email it is for, if any
 * @param createdAt when it was made
 * @param expiresAt when it stops working
 * @param acceptedAt when it was accepted, if it was
 */
record InvitationView(String id, String workspaceId, String role, @Nullable String email, Instant createdAt,
		Instant expiresAt, @Nullable Instant acceptedAt) {

}
