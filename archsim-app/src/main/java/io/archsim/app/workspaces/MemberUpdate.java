package io.archsim.app.workspaces;

import io.archsim.app.access.WorkspaceRole;

/**
 * What {@code PATCH /workspaces/{id}/members/{userId}} takes.
 *
 * @param role the member's new role
 */
record MemberUpdate(WorkspaceRole role) {

}
