package io.archsim.app.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claimed runs' leases in {@code simulation_runs}, as the Python worker kept them:
 * renewed by the worker that holds them, which alone writes their progress and outcome;
 * given back when it stops without finishing them; and reaped by any worker once they run
 * out.
 */
@Repository
@Profile("worker")
class LeaseRepository {

	/**
	 * The attempts a run gets: a run whose worker stops is requeued once, then failed.
	 */
	private static final int MAX_ATTEMPTS = 2;

	/** Why a run failed when its worker stopped without finishing it, twice. */
	private static final String WORKER_STOPPED = "The simulation worker stopped unexpectedly (for example, it ran "
			+ "out of memory or was killed), so this run did not finish. Run it again; if it keeps happening, "
			+ "shorten the run or lower the load.";

	/** A run the worker holds. */
	private static final String HELD = "id = :id AND lease_owner = :worker AND status = 'running'";

	/** A running run whose lease ran out, or that never had one. */
	private static final String EXPIRED = "status = 'running' AND (lease_expires_at IS NULL "
			+ "OR lease_expires_at < :now)";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	LeaseRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Renews a worker's leases on the runs it names, and says which it still holds and
	 * which of those someone asked to stop. A run it holds but does not name runs out.
	 * @param workerId the worker
	 * @param runIds its runs
	 * @param leaseEnd when the renewed leases run out
	 * @return the runs it holds, and those asked to stop
	 */
	@Transactional
	Renewal renew(String workerId, Collection<String> runIds, Instant leaseEnd) {
		if (runIds.isEmpty()) {
			return new Renewal(Set.of(), Set.of());
		}
		String held = "id IN (:ids) AND lease_owner = :worker AND status = 'running'";
		this.jdbc.sql("UPDATE simulation_runs SET lease_expires_at = :leaseEnd WHERE " + held)
			.param("leaseEnd", Timestamp.from(leaseEnd))
			.param("ids", runIds)
			.param("worker", workerId)
			.update();
		List<Map.Entry<String, Boolean>> owned = this.jdbc
			.sql("SELECT id, cancel_requested_at IS NOT NULL AS cancel FROM simulation_runs WHERE " + held)
			.param("ids", runIds)
			.param("worker", workerId)
			.query((row, number) -> Map.entry(row.getString("id"), row.getBoolean("cancel")))
			.list();
		return new Renewal(owned.stream().map(Map.Entry::getKey).collect(Collectors.toSet()),
				owned.stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toSet()));
	}

	/**
	 * Writes a run's progress, if the worker holds it.
	 * @param runId the run
	 * @param workerId the worker
	 * @param progress how far it got
	 * @return {@code false} when the worker lost the run
	 */
	boolean progress(String runId, String workerId, double progress) {
		return this.jdbc.sql("UPDATE simulation_runs SET progress = :progress WHERE " + HELD)
			.param("progress", progress)
			.param("id", runId)
			.param("worker", workerId)
			.update() == 1;
	}

	/**
	 * Writes how a run ended, if the worker holds it: an outcome is written once, by the
	 * worker that holds the run.
	 * @param runId the run
	 * @param workerId the worker
	 * @param outcome how it ended
	 * @param now when it ended
	 * @return {@code false} when the worker lost the run, and the outcome is dropped
	 */
	boolean finish(String runId, String workerId, RunOutcome outcome, Instant now) {
		JdbcClient.StatementSpec update = switch (outcome) {
			case RunOutcome.Succeeded succeeded -> this.jdbc.sql("""
					UPDATE simulation_runs SET status = 'succeeded', finished_at = :now, lease_expires_at = NULL,
					    progress = 1.0, report = CAST(:report AS jsonb), summary = CAST(:summary AS jsonb),
					    input_digest = :inputDigest, result_digest = :resultDigest, wall_seconds = :wallSeconds
					WHERE %s""".formatted(HELD))
				.param("report", this.json.writeValueAsString(succeeded.report()))
				.param("summary", this.json.writeValueAsString(succeeded.summary()))
				.param("inputDigest", succeeded.inputDigest())
				.param("resultDigest", succeeded.resultDigest())
				.param("wallSeconds", succeeded.wallSeconds());
			case RunOutcome.Failed failed -> this.jdbc.sql("""
					UPDATE simulation_runs SET status = 'failed', finished_at = :now, lease_expires_at = NULL,
					    error = CAST(:error AS jsonb)
					WHERE %s""".formatted(HELD)).param("error", this.json.writeValueAsString(failed.error()));
			case RunOutcome.Cancelled _ -> this.jdbc.sql("""
					UPDATE simulation_runs SET status = 'cancelled', finished_at = :now, lease_expires_at = NULL
					WHERE %s""".formatted(HELD));
		};
		return update.param("now", Timestamp.from(now)).param("id", runId).param("worker", workerId).update() == 1;
	}

	/**
	 * Gives back a run its worker stops without finishing, as it stops: cancelled if
	 * someone asked, else queued again without counting the attempt.
	 * @param runId the run
	 * @param workerId the worker
	 * @param now the time
	 * @return what became of the run, or empty when the worker no longer held it
	 */
	@Transactional
	Optional<Released> release(String runId, String workerId, Instant now) {
		int cancelled = this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'cancelled', finished_at = :now, lease_expires_at = NULL
				WHERE %s AND cancel_requested_at IS NOT NULL""".formatted(HELD))
			.param("now", Timestamp.from(now))
			.param("id", runId)
			.param("worker", workerId)
			.update();
		if (cancelled == 1) {
			return Optional.of(Released.CANCELLED);
		}
		int queued = this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'queued', lease_owner = NULL, lease_expires_at = NULL,
				    started_at = NULL, progress = 0.0, attempts = attempts - 1
				WHERE %s""".formatted(HELD)).param("id", runId).param("worker", workerId).update();
		return (queued == 1) ? Optional.of(Released.QUEUED) : Optional.empty();
	}

	/**
	 * Settles the running runs whose lease ran out (their worker died or hung): cancelled
	 * if someone asked, failed after their last attempt, else queued again. Every worker
	 * reaps; each run is settled by one of them.
	 * @param now the time
	 * @return the runs settled
	 */
	@Transactional
	Reaped reap(Instant now) {
		Timestamp at = Timestamp.from(now);
		int cancelled = this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'cancelled', finished_at = :now, lease_expires_at = NULL
				WHERE %s AND cancel_requested_at IS NOT NULL""".formatted(EXPIRED)).param("now", at).update();
		int failed = this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'failed', finished_at = :now, lease_expires_at = NULL,
				    error = CAST(:error AS jsonb)
				WHERE %s AND attempts >= :attempts""".formatted(EXPIRED))
			.param("now", at)
			.param("error", this.json.writeValueAsString(this.json.createObjectNode().put("message", WORKER_STOPPED)))
			.param("attempts", MAX_ATTEMPTS)
			.update();
		int requeued = this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'queued', lease_owner = NULL, lease_expires_at = NULL,
				    started_at = NULL, progress = 0.0
				WHERE %s""".formatted(EXPIRED)).param("now", at).update();
		return new Reaped(requeued, failed, cancelled);
	}

}
