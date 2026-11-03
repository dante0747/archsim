package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * What accepting an invitation would do.
 *
 * @param workspaceName the workspace it joins
 * @param role the role it gives
 * @param email the verified email it is for, if any
 * @param expiresAt when it stops working
 * @param invitedBy who sent it, if they are still here
 */
record InvitationPreview(String workspaceName, String role, @Nullable String email, Instant expiresAt,
		@Nullable String invitedBy) {

}
