package io.archsim.app.shares;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

/**
 * What the tests of share links share: a project with a finished run, whose report has
 * request traces, and links to it.
 */
abstract class ShareTestSupport extends HostedTestSupport {

	static final String DOCUMENT = """
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Shop"},
			 "objectives": {"latencyP95Ms": 200}, "architecture": {"nodes": [], "edges": []}, "workload": {}}""";

	/**
	 * A project in its owner's personal workspace.
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
			.param("document", DOCUMENT)
			.update();
		return id;
	}

	/**
	 * A run of a project, as a worker leaves it.
	 * @param projectId the project
	 * @param status its status; a succeeded run has a report
	 * @return the run's id
	 */
	String run(String projectId, String status) {
		String id = newId();
		this.jdbc()
			.sql("""
					INSERT INTO simulation_runs (id, project_id, workspace_id, label, scenario_name, status, progress,
					                             seed, engine_version, input_digest, result_digest, input_document,
					                             report, created_at)
					SELECT :id, p.id, p.workspace_id, 'Baseline', 'Peak', :status, 1, 7, '0.16.0', 'in-digest',
					       'out-digest', p.document,
					       CASE WHEN :status = 'succeeded' THEN CAST(:report AS jsonb) END, now()
					FROM projects p WHERE p.id = :project""")
			.param("id", id)
			.param("status", status)
			.param("report", "{\"reportVersion\": \"1.4\", \"summary\": {\"latencyP95Ms\": 120}, \"traces\": [1]}")
			.param("project", projectId)
			.update();
		return id;
	}

	/**
	 * Takes the design out of a link's frozen copy, as links made before "Open a copy"
	 * existed have it.
	 * @param slug the link's id
	 */
	void withoutDesign(String slug) {
		this.jdbc()
			.sql("UPDATE shared_reports SET payload = payload - 'document' WHERE slug = :slug")
			.param("slug", slug)
			.update();
	}

	/**
	 * Revokes a link as the Python API did: its payload becomes JSON {@code null}, not
	 * SQL {@code NULL}.
	 * @param slug the link's id
	 */
	void revokedByPython(String slug) {
		this.jdbc()
			.sql("UPDATE shared_reports SET payload = 'null'::jsonb, revoked_at = now() WHERE slug = :slug")
			.param("slug", slug)
			.update();
	}

	MvcTestResult share(SignedIn person, String runId, String body) {
		return send(person, HttpMethod.POST, "/api/v1/runs/" + runId + "/shares", body);
	}

	/**
	 * A link to a new finished run of a new project.
	 * @param owner who owns the project and makes the link
	 * @return the link's slug
	 */
	String sharedRun(SignedIn owner) {
		return created(share(owner, run(project(owner), "succeeded"), "{}")).path("slug").asString();
	}

}
