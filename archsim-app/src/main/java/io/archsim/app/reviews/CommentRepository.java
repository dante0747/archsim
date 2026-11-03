package io.archsim.app.reviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code comments} table. A reply's {@code parent_id} is its thread's first comment,
 * whose deletion takes the replies with it.
 */
@Repository
class CommentRepository {

	private static final String COLUMNS = "id, workspace_id, project_id, anchor_kind, anchor_id, run_id, parent_id, "
			+ "author_id, body, created_at, edited_at, resolved_at, resolved_by";

	/** The most comments a list holds. */
	private static final int MAX_LISTED = 2000;

	private final JdbcClient jdbc;

	CommentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A project's comments, those on one part, run or the project if asked.
	 * @param projectId the project
	 * @param filter what to list
	 * @return the comments, oldest first
	 */
	List<StoredComment> of(String projectId, CommentFilter filter) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM comments WHERE project_id = :project
				AND (CAST(:kind AS varchar) IS NULL OR anchor_kind = :kind)
				AND (CAST(:anchor AS varchar) IS NULL OR anchor_id = :anchor)
				AND (CAST(:run AS varchar) IS NULL OR run_id = :run)
				ORDER BY created_at, id LIMIT :limit""")
			.param("project", projectId)
			.param("kind", filter.anchorKind())
			.param("anchor", filter.anchorId())
			.param("run", filter.runId())
			.param("limit", MAX_LISTED)
			.query((rows, number) -> stored(rows))
			.list();
	}

	Optional<StoredComment> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM comments WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> stored(rows))
			.optional();
	}

	void add(StoredComment comment) {
		this.jdbc.sql("""
				INSERT INTO comments (id, workspace_id, project_id, anchor_kind, anchor_id, run_id, parent_id,
				                      author_id, body, created_at)
				VALUES (:id, :workspace, :project, :kind, :anchor, :run, :parent, :author, :body, :created)""")
			.param("id", comment.id())
			.param("workspace", comment.workspaceId())
			.param("project", comment.projectId())
			.param("kind", comment.anchorKind())
			.param("anchor", comment.anchorId())
			.param("run", comment.runId())
			.param("parent", comment.parentId())
			.param("author", comment.authorId())
			.param("body", comment.body())
			.param("created", Timestamp.from(comment.createdAt()))
			.update();
	}

	void edit(String id, String body, Instant now) {
		this.jdbc.sql("UPDATE comments SET body = :body, edited_at = :now WHERE id = :id")
			.param("body", body)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	/**
	 * Resolves or reopens a thread.
	 * @param id the thread's first comment
	 * @param resolvedAt when it was resolved, or {@code null} to reopen it
	 * @param resolvedBy who resolved it, or {@code null}
	 */
	void resolve(String id, @Nullable Instant resolvedAt, @Nullable String resolvedBy) {
		this.jdbc.sql("UPDATE comments SET resolved_at = :at, resolved_by = :by WHERE id = :id")
			.param("at", (resolvedAt != null) ? Timestamp.from(resolvedAt) : null)
			.param("by", resolvedBy)
			.param("id", id)
			.update();
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM comments WHERE id = :id").param("id", id).update();
	}

	private static StoredComment stored(ResultSet rows) throws SQLException {
		return new StoredComment(rows.getString("id"), rows.getString("workspace_id"), rows.getString("project_id"),
				rows.getString("anchor_kind"), rows.getString("anchor_id"), rows.getString("run_id"),
				rows.getString("parent_id"), rows.getString("author_id"), rows.getString("body"),
				rows.getTimestamp("created_at").toInstant(), instant(rows.getTimestamp("edited_at")),
				instant(rows.getTimestamp("resolved_at")), rows.getString("resolved_by"));
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

}
