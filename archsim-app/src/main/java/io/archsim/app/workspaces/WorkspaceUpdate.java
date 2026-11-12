package io.archsim.app.workspaces;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code PATCH /workspaces/{id}} takes.
 *
 * @param name the workspace's new name, if it changes
 */
@IgnoreUnknownKeys
record WorkspaceUpdate(@MinLength(1) @MaxLength(Workspaces.MAX_NAME) @Nullable String name) {

}
