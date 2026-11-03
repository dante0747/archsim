package io.archsim.app.reviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code review_signoffs} table, with each sign-off's workspace and the label of its
 * run.
 */
@Repository
class SignoffRepository {

	private static final String SELECT_SIGNOFFS = """
			SELECT s.id, p.workspace_id, s.project_id, s.run_id, r.label AS run_label, s.project_revision,
			       s.input_digest, s.result_digest, s.reviewer_id, s.decision, s.note, s.created_at
			FROM review_signoffs s JOIN projects p ON p.id = s.project_id
			LEFT JOIN simulation_runs r ON r.id = s.run_id""";

	/** The most sign-offs a list holds. */
	private static final int MAX_LISTED = 500;

	private final JdbcClient jdbc;

	SignoffRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<StoredSignoff> ofRun(String runId) {
		return this.jdbc.sql(SELECT_SIGNOFFS + " WHERE s.run_id = :run ORDER BY s.created_at DESC LIMIT :limit")
			.param("run", runId)
			.param("limit", MAX_LISTED)
			.query((rows, number) -> stored(rows))
			.list();
	}

	List<StoredSignoff> ofProject(String projectId) {
		return this.jdbc.sql(SELECT_SIGNOFFS + " WHERE s.project_id = :project ORDER BY s.created_at DESC LIMIT :limit")
			.param("project", projectId)
			.param("limit", MAX_LISTED)
			.query((rows, number) -> stored(rows))
			.list();
	}

	Optional<StoredSignoff> find(String id) {
		return this.jdbc.sql(SELECT_SIGNOFFS + " WHERE s.id = :id")
			.param("id", id)
			.query((rows, number) -> stored(rows))
			.optional();
	}

	void add(StoredSignoff signoff) {
		this.jdbc.sql("""
				INSERT INTO review_signoffs (id, project_id, run_id, project_revision, input_digest, result_digest,
				                             reviewer_id, decision, note, created_at)
				VALUES (:id, :project, :run, :revision, :input, :result, :reviewer, :decision, :note, :created)""")
			.param("id", signoff.id())
			.param("project", signoff.projectId())
			.param("run", signoff.runId())
			.param("revision", signoff.projectRevision())
			.param("input", signoff.inputDigest())
			.param("result", signoff.resultDigest())
			.param("reviewer", signoff.reviewerId())
			.param("decision", signoff.decision())
			.param("note", signoff.note())
			.param("created", Timestamp.from(signoff.createdAt()))
			.update();
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM review_signoffs WHERE id = :id").param("id", id).update();
	}

	private static StoredSignoff stored(ResultSet rows) throws SQLException {
		return new StoredSignoff(rows.getString("id"), rows.getString("workspace_id"), rows.getString("project_id"),
				rows.getString("run_id"), rows.getString("run_label"), rows.getInt("project_revision"),
				rows.getString("input_digest"), rows.getString("result_digest"), rows.getString("reviewer_id"),
				rows.getString("decision"), rows.getString("note"), rows.getTimestamp("created_at").toInstant());
	}

}
