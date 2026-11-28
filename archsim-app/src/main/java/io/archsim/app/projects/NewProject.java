package io.archsim.app.projects;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.store.Ids;

/**
 * A project to make.
 *
 * @param workspaceId the workspace to make it in
 * @param design its design, normalized; its name is the project's
 * @param description its description; the design's when {@code null}
 * @param sourceExample the example it was copied from without a name of its own, if any;
 * a rename keeps it
 */
record NewProject(String workspaceId, ObjectNode design, @Nullable String description, @Nullable String sourceExample) {

	/**
	 * The project's first revision: a new id, named as its design is, made and updated
	 * now.
	 * @param createdBy who makes it; {@code null} for no one (the seed)
	 * @param now when
	 * @return the project at revision 1
	 */
	Project firstRevision(@Nullable String createdBy, Instant now) {
		JsonNode metadata = this.design.path("metadata");
		String about = (this.description != null) ? this.description : metadata.path("description").asString(null);
		return new Project(Ids.newId(), this.workspaceId, createdBy, metadata.path("name").asString(), about,
				this.design, 1, this.sourceExample, now, now);
	}

}
