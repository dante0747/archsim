package io.archsim.app.workspaces;

import org.jspecify.annotations.Nullable;

/**
 * An invitation that can still be accepted, with what accepting it shows.
 *
 * @param view the invitation
 * @param workspaceName the workspace it joins
 * @param invitedBy who sent it, if they are still here
 */
record OpenInvitation(InvitationView view, String workspaceName, @Nullable String invitedBy) {

}
