package io.archsim.app.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.database.TestDatabases;

/**
 * A run queue of its own for a test: a migrated database, runs queued in it with the
 * columns the API writes, the API's cancel request, and the rows read back. Runs are
 * queued a millisecond apart, so their order is their creation's.
 */
final class QueueFixture {

	/** When the first run is queued. */
	static final Instant QUEUED = Instant.parse("2026-11-09T11:00:00Z");

	private final DataSource database = new DriverManagerDataSource(TestDatabases.migratedJdbcUrl());

	private final JdbcClient jdbc = JdbcClient.create(this.database);

	private final AtomicInteger queued = new AtomicInteger();

	DataSource database() {
		return this.database;
	}

	JdbcClient jdbc() {
		return this.jdbc;
	}

	/**
	 * Queues runs in a team workspace, made the first time.
	 * @param workspace the workspace
	 * @param count how many runs
	 * @return their ids, oldest first
	 */
	List<String> queue(String workspace, int count) {
		return queue(workspace, "team", count);
	}

	/**
	 * Queues runs in a workspace of a kind, made the first time.
	 * @param workspace the workspace
	 * @param kind its kind
	 * @param count how many runs
	 * @return their ids, oldest first
	 */
	List<String> queue(String workspace, String kind, int count) {
		this.jdbc.sql("""
				INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, :id, :kind, :at)
				ON CONFLICT (id) DO NOTHING""")
			.param("id", workspace)
			.param("kind", kind)
			.param("at", at(QUEUED))
			.update();
		this.jdbc.sql("""
				INSERT INTO projects (id, name, document, schema_version, revision, created_at, updated_at,
				    workspace_id)
				VALUES (:id, 'p', '{}', '1.7', 1, :at, :at, :id) ON CONFLICT (id) DO NOTHING""")
			.param("id", workspace)
			.param("at", at(QUEUED))
			.update();
		return IntStream.range(0, count).mapToObj((index) -> queueOne(workspace)).toList();
	}

	/**
	 * Gives a workspace a cap of its own on runs at once.
	 * @param workspace the workspace
	 * @param maxConcurrentRuns its cap
	 */
	void cap(String workspace, int maxConcurrentRuns) {
		this.jdbc.sql("UPDATE workspaces SET max_concurrent_runs = :cap WHERE id = :id")
			.param("cap", maxConcurrentRuns)
			.param("id", workspace)
			.update();
	}

	/**
	 * Asks for a run to stop, as the API does: a queued run is cancelled now, a running
	 * one marked for its worker.
	 * @param runId the run
	 * @param now the time of the request
	 */
	void requestCancel(String runId, Instant now) {
		this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'cancelled', finished_at = :now, cancel_requested_at = :now
				WHERE id = :id AND status = 'queued'""").param("now", at(now)).param("id", runId).update();
		this.jdbc.sql("""
				UPDATE simulation_runs SET cancel_requested_at = COALESCE(cancel_requested_at, :now)
				WHERE id = :id AND status = 'running'""").param("now", at(now)).param("id", runId).update();
	}

	/**
	 * Moves a run's lease, as time passing would.
	 * @param runId the run
	 * @param expiresAt when its lease runs out, or {@code null} for none
	 */
	void lease(String runId, @Nullable Instant expiresAt) {
		this.jdbc.sql("UPDATE simulation_runs SET lease_expires_at = :at WHERE id = :id")
			.param("at", (expiresAt != null) ? at(expiresAt) : null)
			.param("id", runId)
			.update();
	}

	/**
	 * Makes a run a quick check.
	 * @param runId the run
	 */
	void sampled(String runId) {
		this.jdbc.sql("UPDATE simulation_runs SET mode = 'sampled' WHERE id = :id").param("id", runId).update();
	}

	/**
	 * Hands a running run to another worker, as a reap and a claim elsewhere would.
	 * @param runId the run
	 * @param workerId the worker that takes it
	 */
	void takeOver(String runId, String workerId) {
		this.jdbc.sql("UPDATE simulation_runs SET lease_owner = :worker WHERE id = :id")
			.param("worker", workerId)
			.param("id", runId)
			.update();
	}

	/**
	 * A column of a run's row, as text.
	 * @param runId the run
	 * @param column the column
	 * @return its value
	 */
	@Nullable String text(String runId, String column) {
		return this.jdbc.sql("SELECT CAST(" + column + " AS text) FROM simulation_runs WHERE id = :id")
			.param("id", runId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/**
	 * Sets how many times a run was claimed.
	 * @param runId the run
	 * @param attempts its attempts
	 */
	void attempts(String runId, int attempts) {
		this.jdbc.sql("UPDATE simulation_runs SET attempts = :attempts WHERE id = :id")
			.param("attempts", attempts)
			.param("id", runId)
			.update();
	}

	/**
	 * A run's row, as the worker leaves it.
	 * @param runId the run
	 * @return its row
	 */
	RunRow row(String runId) {
		return this.jdbc.sql("""
				SELECT status, lease_owner, lease_expires_at, started_at, finished_at, cancel_requested_at, attempts,
				    progress, CAST(error AS text) AS error
				FROM simulation_runs WHERE id = :id""").param("id", runId).query(QueueFixture::runRow).single();
	}

	private String queueOne(String workspace) {
		int index = this.queued.getAndIncrement();
		String id = "%s-%02d".formatted(workspace, index);
		this.jdbc.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, status, progress, seed, engine_version,
				    input_document, max_requests, max_wall_seconds, mode, attempts, created_at)
				VALUES (:id, :workspace, :workspace, 'queued', 0, :seed, '0.16.0', CAST(:document AS jsonb), 1000,
				    20.0, 'full', 0, :at)""")
			.param("id", id)
			.param("workspace", workspace)
			.param("seed", index)
			.param("document", "{\"n\": " + index + "}")
			.param("at", at(QUEUED.plusMillis(index)))
			.update();
		return id;
	}

	private static RunRow runRow(ResultSet row, int number) throws SQLException {
		return new RunRow(row.getString("status"), row.getString("lease_owner"), instant(row, "lease_expires_at"),
				instant(row, "started_at"), instant(row, "finished_at"), instant(row, "cancel_requested_at"),
				row.getInt("attempts"), row.getDouble("progress"), row.getString("error"));
	}

	private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return (value != null) ? value.toInstant() : null;
	}

	private static Timestamp at(Instant instant) {
		return Timestamp.from(instant);
	}

	/**
	 * A run's row: where it is in its life, whose lease it is under, and its error.
	 *
	 * @param status queued, running, succeeded, failed or cancelled
	 * @param leaseOwner the worker that holds or last held it
	 * @param leaseExpiresAt when its lease runs out
	 * @param startedAt when a worker last claimed it
	 * @param finishedAt when it ended
	 * @param cancelRequestedAt when someone asked it to stop
	 * @param attempts how many times it was claimed
	 * @param progress how far it got
	 * @param error its error, as JSON
	 */
	record RunRow(String status, @Nullable String leaseOwner, @Nullable Instant leaseExpiresAt,
			@Nullable Instant startedAt, @Nullable Instant finishedAt, @Nullable Instant cancelRequestedAt,
			int attempts, double progress, @Nullable String error) {

	}

}
