package io.archsim.app.projects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A project to make.
 *
 * @param workspaceId the workspace to make it in
 * @param design its design, normalized; its name is the project's
 * @param description its description; the design's when {@code null}
 * @param sourceExample the example it is an unrenamed copy of, if any
 */
record NewProject(String workspaceId, ObjectNode design, @Nullable String description, @Nullable String sourceExample) {

}
