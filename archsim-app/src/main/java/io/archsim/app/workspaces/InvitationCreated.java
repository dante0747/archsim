package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * An invitation just made, with its token: shown in this answer only. The link to share
 * is {@code <web address>/invite/<token>}.
 *
 * @param id the invitation's id
 * @param workspaceId the workspace
 * @param role the role it gives
 * @param email the verified email it is for, if any
 * @param createdAt when it was made
 * @param expiresAt when it stops working
 * @param acceptedAt never, yet
 * @param token the token
 */
record InvitationCreated(String id, String workspaceId, String role, @Nullable String email, Instant createdAt,
		Instant expiresAt, @Nullable Instant acceptedAt, String token) {

	static InvitationCreated of(InvitationView view, String token) {
		return new InvitationCreated(view.id(), view.workspaceId(), view.role(), view.email(), view.createdAt(),
				view.expiresAt(), view.acceptedAt(), token);
	}

}
