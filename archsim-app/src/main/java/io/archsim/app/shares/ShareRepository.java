package io.archsim.app.shares;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code shared_reports} table. A share's payload, the frozen copy of a run's
 * results, is read only where a link is opened.
 */
@Repository
class ShareRepository {

	private static final String COLUMNS = "id, slug, workspace_id, project_id, run_id, created_by, title, "
			+ "payload_version, report_version, result_digest, created_at, expires_at, revoked_at, view_count";

	/** The most shares a list holds. */
	private static final int MAX_LISTED = 100;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ShareRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	void add(StoredShare share) {
		this.jdbc.sql("""
				INSERT INTO shared_reports (id, slug, workspace_id, project_id, run_id, created_by, title, payload,
				                            payload_version, report_version, result_digest, created_at, expires_at,
				                            view_count)
				VALUES (:id, :slug, :workspace, :project, :run, :createdBy, :title, CAST(:payload AS jsonb),
				        :payloadVersion, :reportVersion, :resultDigest, :createdAt, :expiresAt, 0)""")
			.param("id", share.id())
			.param("slug", share.slug())
			.param("workspace", share.workspaceId())
			.param("project", share.projectId())
			.param("run", share.runId())
			.param("createdBy", share.createdBy())
			.param("title", share.title())
			.param("payload", (share.payload() != null) ? this.json.writeValueAsString(share.payload()) : null)
			.param("payloadVersion", share.payloadVersion())
			.param("reportVersion", share.reportVersion())
			.param("resultDigest", share.resultDigest())
			.param("createdAt", Timestamp.from(share.createdAt()))
			.param("expiresAt", timestamp(share.expiresAt()))
			.update();
	}

	/**
	 * A share, without its payload.
	 * @param id the share
	 * @return the share, if it exists
	 */
	Optional<StoredShare> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + ", NULL AS payload FROM shared_reports WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> stored(rows))
			.optional();
	}

	/**
	 * The share a link opens, with its payload. A link the Python API revoked kept JSON
	 * {@code null} as its payload, which counts as none.
	 * @param slug the link's id
	 * @return the share, if there is one with that slug
	 */
	Optional<StoredShare> withSlug(String slug) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + ", CASE WHEN jsonb_typeof(payload) = 'object' THEN payload::text END AS payload "
					+ "FROM shared_reports WHERE slug = :slug")
			.param("slug", slug)
			.query((rows, number) -> stored(rows))
			.optional();
	}

	List<StoredShare> ofProject(String projectId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + ", NULL AS payload FROM shared_reports WHERE project_id = :project "
					+ "ORDER BY created_at DESC LIMIT :limit")
			.param("project", projectId)
			.param("limit", MAX_LISTED)
			.query((rows, number) -> stored(rows))
			.list();
	}

	List<StoredShare> ofWorkspace(String workspaceId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + ", NULL AS payload FROM shared_reports WHERE workspace_id = :workspace "
					+ "ORDER BY created_at DESC LIMIT :limit")
			.param("workspace", workspaceId)
			.param("limit", MAX_LISTED)
			.query((rows, number) -> stored(rows))
			.list();
	}

	/**
	 * Stamps a link revoked, unless it already is. Of two revoking it at once, one stamps
	 * it; the other waits for that to commit, then finds it stamped.
	 * @param id the share
	 * @param now the time it is
	 * @return {@code true} when this call stamped it
	 */
	boolean stampRevoked(String id, Instant now) {
		return this.jdbc.sql("UPDATE shared_reports SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update() == 1;
	}

	/**
	 * Deletes a link's frozen copy.
	 * @param id the share
	 */
	void clearPayload(String id) {
		this.jdbc.sql("UPDATE shared_reports SET payload = NULL WHERE id = :id").param("id", id).update();
	}

	/**
	 * Counts one more opening of a link.
	 * @param id the share
	 */
	void countView(String id) {
		this.jdbc.sql("UPDATE shared_reports SET view_count = view_count + 1 WHERE id = :id").param("id", id).update();
	}

	private StoredShare stored(ResultSet rows) throws SQLException {
		String payload = rows.getString("payload");
		return new StoredShare(rows.getString("id"), rows.getString("slug"), rows.getString("workspace_id"),
				rows.getString("project_id"), rows.getString("run_id"), rows.getString("created_by"),
				rows.getString("title"), (payload != null) ? (ObjectNode) this.json.readTree(payload) : null,
				rows.getInt("payload_version"), rows.getString("report_version"), rows.getString("result_digest"),
				rows.getTimestamp("created_at").toInstant(), instant(rows.getTimestamp("expires_at")),
				instant(rows.getTimestamp("revoked_at")), rows.getInt("view_count"));
	}

	private static @Nullable Timestamp timestamp(@Nullable Instant time) {
		return (time != null) ? Timestamp.from(time) : null;
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

}
