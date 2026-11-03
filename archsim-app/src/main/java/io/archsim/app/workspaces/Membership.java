package io.archsim.app.workspaces;

/**
 * A workspace someone belongs to, with their role there.
 *
 * @param workspace the workspace
 * @param role the member's role
 */
record Membership(WorkspaceInfo workspace, String role) {

}
