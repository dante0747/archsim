package io.archsim.app.limits;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a workspace stores, as PostgreSQL stores the values ({@code pg_column_size}:
 * compressed, without TOAST's own overhead, indexes or old row versions), across the
 * tables that hold designs and results.
 */
@Repository
class StorageRepository {

	/**
	 * A workspace's stored bytes. {@code IN} matches a design once however many of the
	 * workspace's runs simulate it, so each counts once.
	 */
	private static final String STORED_BYTES = """
			SELECT coalesce((SELECT sum(pg_column_size(document)) FROM projects WHERE workspace_id = :id), 0)
			     + coalesce((SELECT sum(coalesce(pg_column_size(report), 0) + coalesce(pg_column_size(summary), 0))
			                 FROM simulation_runs WHERE workspace_id = :id), 0)
			     + coalesce((SELECT sum(pg_column_size(document)) FROM run_designs
			                 WHERE digest IN (SELECT design_digest FROM simulation_runs WHERE workspace_id = :id)), 0)
			     + coalesce((SELECT sum(pg_column_size(payload))
			                 FROM shared_reports WHERE workspace_id = :id), 0)""";

	private final JdbcClient jdbc;

	StorageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A workspace's kind.
	 * @param workspaceId the workspace
	 * @return its kind, or empty for a workspace that does not exist
	 */
	Optional<String> kind(String workspaceId) {
		return this.jdbc.sql("SELECT kind FROM workspaces WHERE id = :id")
			.param("id", workspaceId)
			.query(String.class)
			.optional();
	}

	/**
	 * The bytes a workspace stores: its projects' designs, its runs' reports and
	 * summaries, the designs its runs simulate, and its share links' copies. A design is
	 * stored once however many runs simulate it (ADR-0110), and counts once in each
	 * workspace whose runs simulate it, in full: what a workspace stores then depends on
	 * its own rows only, says nothing of other workspaces, and falls when its own runs
	 * go.
	 * @param workspaceId the workspace
	 * @return the bytes
	 */
	long storedBytes(String workspaceId) {
		return this.jdbc.sql(STORED_BYTES).param("id", workspaceId).query(Long.class).single();
	}

}
