package io.archsim.app.projects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a project's next revision saves.
 *
 * @param name its name, also its design's
 * @param description its description
 * @param design its design, normalized
 * @param expected the revision the save was made from, as the editor says or as read
 */
record Revision(String name, @Nullable String description, ObjectNode design, int expected) {

}
