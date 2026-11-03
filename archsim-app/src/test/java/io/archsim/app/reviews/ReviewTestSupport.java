package io.archsim.app.reviews;

import org.springframework.http.HttpMethod;

import io.archsim.app.support.HostedTestSupport;

/**
 * What the tests of comments, sign-offs and the history share: a project with a node and
 * an edge, runs of it as workers leave them, and comments on it.
 */
abstract class ReviewTestSupport extends HostedTestSupport {

	static final String PROJECTS = "/api/v1/projects/";

	static final String COMMENTS = "/api/v1/comments/";

	/**
	 * A project in its owner's personal workspace, saved once, with a node {@code api}
	 * and an edge {@code e1}.
	 * @param owner whose workspace it is in
	 * @return the project's id
	 */
	String project(SignedIn owner) {
		String id = newId();
		this.jdbc()
			.sql("""
					INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
					                      created_at, updated_at)
					VALUES (:id, :workspace, :user, 'Shop', CAST(:document AS jsonb), '1.7', 1, now(), now())""")
			.param("id", id)
			.param("workspace", owner.workspaceId())
			.param("user", owner.userId())
			.param("document", """
					{"metadata": {"name": "Shop"},
					 "architecture": {"nodes": [{"id": "api"}, {"id": "db"}], "edges": [{"id": "e1"}]}}""")
			.update();
		return id;
	}

	/**
	 * A run of a project, as a worker leaves it.
	 * @param projectId the project
	 * @param status {@code queued}, {@code running}, {@code succeeded}, {@code failed} or
	 * {@code cancelled}
	 * @return the run's id
	 */
	String run(String projectId, String status) {
		String id = newId();
		this.jdbc()
			.sql("""
					INSERT INTO simulation_runs (id, project_id, workspace_id, label, status, progress, seed,
					                             engine_version, input_digest, result_digest, input_document,
					                             created_at)
					SELECT :id, p.id, p.workspace_id, 'Baseline', :status, 1, 7, '0.16.0', 'in-digest', 'out-digest',
					       p.document, now()
					FROM projects p WHERE p.id = :project""")
			.param("id", id)
			.param("status", status)
			.param("project", projectId)
			.update();
		return id;
	}

	/**
	 * Saves a project again, as an editor does.
	 * @param projectId the project
	 */
	void save(String projectId) {
		this.jdbc()
			.sql("UPDATE projects SET revision = revision + 1, document = jsonb_set(document, '{architecture,nodes}', "
					+ "'[{\"id\": \"api\"}]') WHERE id = :id")
			.param("id", projectId)
			.update();
	}

	/**
	 * A comment written through the API.
	 * @param author who writes it
	 * @param projectId the project
	 * @param body the request's body
	 * @return the comment
	 */
	String comment(SignedIn author, String projectId, String body) {
		return created(send(author, HttpMethod.POST, PROJECTS + projectId + "/comments", body)).path("id").asString();
	}

}
