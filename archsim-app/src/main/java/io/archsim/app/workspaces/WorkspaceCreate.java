package io.archsim.app.workspaces;

import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /workspaces} takes.
 *
 * @param name the team workspace's name
 */
record WorkspaceCreate(@MinLength(1) @MaxLength(Workspaces.MAX_NAME) String name) {

}
