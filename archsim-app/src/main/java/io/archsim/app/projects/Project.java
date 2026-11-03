package io.archsim.app.projects;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A saved design, as the {@code projects} table holds it.
 *
 * @param id the project's id, a UUID's 32 hex digits
 * @param workspaceId the workspace it belongs to
 * @param createdBy who made it, if that user still exists
 * @param name its name, also the document's {@code metadata.name}
 * @param description what it is for, if said
 * @param document the design, normalized to the current schema
 * @param revision how many times it was saved, from 1
 * @param sourceExample the example it is an unrenamed copy of, if any
 * @param createdAt when it was made
 * @param updatedAt when it was last saved
 */
record Project(String id, String workspaceId, @Nullable String createdBy, String name, @Nullable String description,
		ObjectNode document, int revision, @Nullable String sourceExample, Instant createdAt, Instant updatedAt) {

	/**
	 * The schema version the document is written in.
	 * @return the document's {@code schemaVersion}
	 */
	String schemaVersion() {
		return this.document.path("schemaVersion").asString();
	}

}
