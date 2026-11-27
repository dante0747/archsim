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
	 * The bytes a workspace stores: its projects' designs, its runs' designs, reports and
	 * summaries, and its share links' copies.
	 * @param workspaceId the workspace
	 * @return the bytes
	 */
	long storedBytes(String workspaceId) {
		return this.jdbc.sql("""
				SELECT coalesce((SELECT sum(pg_column_size(document)) FROM projects WHERE workspace_id = :id), 0)
				     + coalesce((SELECT sum(pg_column_size(input_document) + coalesce(pg_column_size(report), 0)
				                            + coalesce(pg_column_size(summary), 0))
				                 FROM simulation_runs WHERE workspace_id = :id), 0)
				     + coalesce((SELECT sum(pg_column_size(payload))
				                 FROM shared_reports WHERE workspace_id = :id), 0)""")
			.param("id", workspaceId)
			.query(Long.class)
			.single();
	}

}
