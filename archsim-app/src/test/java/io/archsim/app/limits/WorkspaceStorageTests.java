package io.archsim.app.limits;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.support.FullWorkspaces;
import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * What a workspace stores, as PostgreSQL stores it: its projects' designs, its runs'
 * designs and reports, and its share links' copies, and no other workspace's; the quota
 * of its kind, and the lock a check in turn holds (ADR-0109).
 */
class WorkspaceStorageTests extends HostedTestSupport {

	/** A JSON object holding a megabyte PostgreSQL cannot compress, as SQL. */
	private static final String PADDED = "jsonb_build_object('padding', "
			+ FullWorkspaces.incompressible(WorkspaceLimits.MEGABYTE) + ")";

	private static final String EMPTY = "'{}'::jsonb";

	@Autowired
	private WorkspaceStorage storage;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void storedBytes_aProjectsDesign_countsWhatItStores() {
		String workspace = workspace("personal");
		long before = this.storage.storedBytes(workspace);

		project(workspace, PADDED);

		assertThat(this.storage.storedBytes(workspace)).isGreaterThanOrEqualTo(before + WorkspaceLimits.MEGABYTE);
	}

	@Test
	void storedBytes_aRunsDesign_countsWhatItStores() {
		String workspace = workspace("personal");
		String project = project(workspace, EMPTY);
		long before = this.storage.storedBytes(workspace);

		run(workspace, project, PADDED, "NULL");

		assertThat(this.storage.storedBytes(workspace)).isGreaterThanOrEqualTo(before + WorkspaceLimits.MEGABYTE);
	}

	@Test
	void storedBytes_aRunsReport_countsWhatItStores() {
		String workspace = workspace("personal");
		String project = project(workspace, EMPTY);
		long before = this.storage.storedBytes(workspace);

		run(workspace, project, EMPTY, PADDED);

		assertThat(this.storage.storedBytes(workspace)).isGreaterThanOrEqualTo(before + WorkspaceLimits.MEGABYTE);
	}

	@Test
	void storedBytes_aRunsSummary_countsWhatItStores() {
		String workspace = workspace("personal");
		String run = run(workspace, project(workspace, EMPTY), EMPTY, "NULL");
		long before = this.storage.storedBytes(workspace);

		jdbc().sql("UPDATE simulation_runs SET summary = %s WHERE id = :id".formatted(PADDED))
			.param("id", run)
			.update();

		assertThat(this.storage.storedBytes(workspace)).isGreaterThanOrEqualTo(before + WorkspaceLimits.MEGABYTE);
	}

	@Test
	void storedBytes_aShareLinksCopy_countsWhatItStores() {
		String workspace = workspace("team");
		long before = this.storage.storedBytes(workspace);

		jdbc().sql("""
				INSERT INTO shared_reports (id, slug, workspace_id, title, payload, payload_version, report_version,
				                            created_at)
				VALUES (:id, :id, :workspace, 'Shop', %s, 1, '1.21', now())""".formatted(PADDED))
			.param("id", newId())
			.param("workspace", workspace)
			.update();

		assertThat(this.storage.storedBytes(workspace)).isGreaterThanOrEqualTo(before + WorkspaceLimits.MEGABYTE);
	}

	@Test
	void storedBytes_anotherWorkspacesDesign_isNotCounted() {
		String workspace = workspace("personal");
		String other = workspace("personal");
		long before = this.storage.storedBytes(workspace);

		project(other, PADDED);

		assertThat(this.storage.storedBytes(workspace)).isEqualTo(before);
	}

	@Test
	void requireRoom_aSandboxStoringItsQuota_isAConflict() {
		String workspace = workspace("sandbox");
		FullWorkspaces.fillSandbox(jdbc(), project(workspace, "'{\"metadata\": {}}'::jsonb"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.storage.requireRoom(workspace))
			.withMessage(FullWorkspaces.FULL_SANDBOX);
	}

	@Test
	void requireRoom_aSandboxBelowItsQuota_letsItThrough() {
		String workspace = workspace("sandbox");
		project(workspace, PADDED);

		assertThatNoException().isThrownBy(() -> this.storage.requireRoom(workspace));
	}

	@Test
	void requireRoomInTurn_withinATransaction_locksTheWorkspaceUntilItEnds() {
		String workspace = workspace("personal");
		JdbcClient other = anotherConnection();

		this.transactions.executeWithoutResult((status) -> {
			this.storage.requireRoomInTurn(workspace);

			assertThatExceptionOfType(DataAccessException.class)
				.isThrownBy(() -> other.sql("SELECT id FROM workspaces WHERE id = :id FOR UPDATE NOWAIT")
					.param("id", workspace)
					.query(String.class)
					.list());
		});
	}

	private String workspace(String kind) {
		String id = newId();
		jdbc().sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, 'Lab', :kind, now())")
			.param("id", id)
			.param("kind", kind)
			.update();
		return id;
	}

	private String project(String workspace, String document) {
		String id = newId();
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES (:id, :workspace, 'Lab', %s, '1.7', 1, now(), now())""".formatted(document))
			.param("id", id)
			.param("workspace", workspace)
			.update();
		return id;
	}

	/**
	 * A finished run of a project, as a worker leaves it.
	 * @param workspace the project's workspace
	 * @param project the project
	 * @param design the design the run stores, as SQL
	 * @param report its report, as SQL
	 * @return the run's id
	 */
	private String run(String workspace, String project, String design, String report) {
		String id = newId();
		jdbc()
			.sql("""
					INSERT INTO simulation_runs (id, project_id, workspace_id, label, status, progress, seed,
					                             engine_version, input_document, report, created_at)
					VALUES (:id, :project, :workspace, 'Baseline', 'succeeded', 1, 7, '1.6.0', %s, %s, now())"""
				.formatted(design, report))
			.param("id", id)
			.param("project", project)
			.param("workspace", workspace)
			.update();
		return id;
	}

}
