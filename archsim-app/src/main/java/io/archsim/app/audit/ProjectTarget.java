package io.archsim.app.audit;

/**
 * A project a change was made to, as the history names it.
 *
 * @param id the project's id
 * @param workspaceId its workspace
 * @param name its name at the time
 */
public record ProjectTarget(String id, String workspaceId, String name) {

}
