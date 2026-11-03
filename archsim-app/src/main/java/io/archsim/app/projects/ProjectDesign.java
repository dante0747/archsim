package io.archsim.app.projects;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.WorkspaceRole;

/**
 * A project's saved design, as other features see it, with the asker's role there.
 *
 * @param id the project's id
 * @param workspaceId its workspace
 * @param name its name
 * @param revision how many times it has been saved
 * @param document its design
 * @param role the asker's role in its workspace
 */
public record ProjectDesign(String id, String workspaceId, String name, int revision, ObjectNode document,
		WorkspaceRole role) {

}
