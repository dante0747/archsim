package io.archsim.app.workspaces;

import org.jspecify.annotations.Nullable;

import io.archsim.app.access.WorkspaceRole;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;

/**
 * What {@code POST /workspaces/{id}/invitations} takes.
 *
 * @param role the role the invitation gives
 * @param email only someone signed in with this verified email can accept it
 * @param expiresInDays days until the link stops working
 */
@IgnoreUnknownKeys
record InvitationCreate(@Default("editor") WorkspaceRole role,
		@MaxLength(320) @Matches("^[^@\\s]+@[^@\\s]+$") @Nullable String email,
		@Default("7") @Ge(1) @Le(30) int expiresInDays) {

}
