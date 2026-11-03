package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * An invitation to store.
 *
 * @param id its id
 * @param tokenHash its token's SHA-256
 * @param workspaceId the workspace it joins
 * @param role the role it gives
 * @param email the verified email it is for, lower case, if any
 * @param createdBy who sends it
 * @param createdAt when it is sent
 * @param expiresAt when it stops working
 */
record NewInvitation(String id, String tokenHash, String workspaceId, String role, @Nullable String email,
		String createdBy, Instant createdAt, Instant expiresAt) {

}
