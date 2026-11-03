package io.archsim.app.projects;

import tools.jackson.databind.node.ObjectNode;

/**
 * A project's saved design, as other features see it.
 *
 * @param id the project's id
 * @param workspaceId its workspace
 * @param document its design
 */
public record ProjectDesign(String id, String workspaceId, ObjectNode document) {

}
