package io.archsim.app.projects;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A project as lists show it.
 *
 * @param id the project's id
 * @param name its name
 * @param description what it is for, if said
 * @param revision how many times it was saved
 * @param createdAt when it was made
 * @param updatedAt when it was last saved
 * @param nodeCount how many parts its design has
 * @param scenarioCount how many scenarios its design has
 */
record ProjectSummary(String id, String name, @Nullable String description, int revision, Instant createdAt,
		Instant updatedAt, int nodeCount, int scenarioCount) {

	static ProjectSummary of(Project project) {
		return new ProjectSummary(project.id(), project.name(), project.description(), project.revision(),
				project.createdAt(), project.updatedAt(), project.document().path("architecture").path("nodes").size(),
				project.document().path("scenarios").size());
	}

}
