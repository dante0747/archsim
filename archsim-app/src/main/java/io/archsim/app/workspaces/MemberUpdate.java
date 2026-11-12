package io.archsim.app.workspaces;

import io.archsim.app.access.WorkspaceRole;
import io.archsim.model.codec.IgnoreUnknownKeys;

/**
 * What {@code PATCH /workspaces/{id}/members/{userId}} takes.
 *
 * @param role the member's new role
 */
@IgnoreUnknownKeys
record MemberUpdate(WorkspaceRole role) {

}
