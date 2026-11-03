package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A member of a workspace.
 *
 * @param userId the member's user id
 * @param name their name
 * @param email their verified email, if any
 * @param avatarUrl their picture, if any
 * @param role their role
 * @param joinedAt when they joined
 */
record MemberView(String userId, String name, @Nullable String email, @Nullable String avatarUrl, String role,
		Instant joinedAt) {

}
