package io.archsim.app.projects;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A project with its design, as the editor opens it.
 *
 * @param id the project's id
 * @param name its name
 * @param description what it is for, if said
 * @param revision how many times it was saved
 * @param createdAt when it was made
 * @param updatedAt when it was last saved
 * @param nodeCount how many parts its design has
 * @param scenarioCount how many scenarios its design has
 * @param document the design
 */
record ProjectView(String id, String name, @Nullable String description, int revision, Instant createdAt,
		Instant updatedAt, int nodeCount, int scenarioCount, ObjectNode document) {

	static ProjectView of(Project project) {
		ProjectSummary summary = ProjectSummary.of(project);
		return new ProjectView(summary.id(), summary.name(), summary.description(), summary.revision(),
				summary.createdAt(), summary.updatedAt(), summary.nodeCount(), summary.scenarioCount(),
				project.document());
	}

}
