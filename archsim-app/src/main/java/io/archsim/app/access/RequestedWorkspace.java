package io.archsim.app.access;

import org.jspecify.annotations.Nullable;

/**
 * The workspace a list or create request names: {@code ?workspace=}, else the
 * {@code X-ArchSim-Workspace} header. A controller takes it as a parameter.
 *
 * @param id the workspace's id, or {@code null} for the asker's own
 */
public record RequestedWorkspace(@Nullable String id) {

}
