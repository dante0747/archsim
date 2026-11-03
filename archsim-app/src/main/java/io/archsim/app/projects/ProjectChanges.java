package io.archsim.app.projects;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.ProjectTarget;
import io.archsim.app.web.ApiException;

/**
 * Writes projects, and records each change in the history in the same transaction, which
 * the caller must have begun.
 */
@Component
class ProjectChanges {

	private final ProjectRepository projects;

	private final AuditLog audit;

	private final ProjectRoom room;

	private final Clock clock;

	ProjectChanges(ProjectRepository projects, AuditLog audit, ProjectRoom room, Clock clock) {
		this.projects = projects;
		this.audit = audit;
		this.room = room;
		this.clock = clock;
	}

	/**
	 * Makes a project, named as its design is.
	 * @param project the project to make
	 * @param principal who makes it
	 * @return the project
	 * @throws io.archsim.app.web.ApiException {@code 409} when its workspace holds as
	 * many projects as it may
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Project create(NewProject project, Principal principal) {
		this.room.check(project.workspaceId());
		Instant now = now();
		JsonNode metadata = project.design().path("metadata");
		String description = (project.description() != null) ? project.description()
				: metadata.path("description").asString(null);
		Project made = new Project(UUID.randomUUID().toString().replace("-", ""), project.workspaceId(),
				principal.userId(), metadata.path("name").asString(), description, project.design(), 1,
				project.sourceExample(), now, now);
		this.projects.insert(made);
		this.audit.project("project.create", target(made), meta("fromExample", made.sourceExample()), principal);
		return made;
	}

	/**
	 * Saves a project's next revision.
	 * @param project the project as stored
	 * @param revision what the revision saves
	 * @param principal who saves it
	 * @return the project as saved
	 * @throws ApiException {@code 409} when another save got there first
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Project update(Project project, Revision revision, Principal principal) {
		Project saved = new Project(project.id(), project.workspaceId(), project.createdBy(), revision.name(),
				revision.description(), revision.design(), project.revision() + 1, project.sourceExample(),
				project.createdAt(), now());
		if (!this.projects.update(saved, project.revision())) {
			int stored = this.projects.find(project.id())
				.map(Project::revision)
				.orElseThrow(() -> Access.notFound("Project '" + project.id() + "'"));
			throw modifiedElsewhere(stored, revision.expected());
		}
		this.audit.project("project.update", target(saved), meta("revision", saved.revision()), principal);
		return saved;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void delete(Project project, Principal principal) {
		this.audit.project("project.delete", target(project), Map.of(), principal);
		this.projects.delete(project.id());
	}

	/**
	 * The refusal of a save made from a revision that is not the stored one.
	 * @param stored the stored revision
	 * @param had the revision the save was made from
	 * @return {@code 409}, asking to reload
	 */
	static ApiException modifiedElsewhere(int stored, int had) {
		return new ApiException(HttpStatus.CONFLICT,
				"Project was modified elsewhere (revision %d, you had %d). Reload before saving.".formatted(stored,
						had),
				List.of());
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static ProjectTarget target(Project project) {
		return new ProjectTarget(project.id(), project.workspaceId(), project.name());
	}

	private static Map<String, @Nullable Object> meta(String key, @Nullable Object value) {
		Map<String, @Nullable Object> meta = new HashMap<>();
		meta.put(key, value);
		return meta;
	}

}
