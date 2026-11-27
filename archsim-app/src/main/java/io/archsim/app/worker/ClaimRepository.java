package io.archsim.app.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.simulator.SimulationMode;

/**
 * The queue as a claim reads and takes it, in {@code simulation_runs}, the designs its
 * runs simulate ({@code run_designs}) and {@code workspaces}: the queued runs in fair
 * order, the workspaces' caps and the runs they have running, and the runs a worker takes
 * under its lease. Each step is meant to run in the claim's transaction, after
 * {@link #takeTurn()}.
 */
@Repository
@Profile("worker")
class ClaimRepository {

	/** The advisory lock claims take turns on: "archsim" and 2, the Python worker's. */
	private static final long CLAIM_LOCK = 0x6172_6368_7369_6D02L;

	/** Queued runs, fewer those the worker is still executing when it names any. */
	private static final String NOT_EXCLUDED = "AND q.id NOT IN (:exclude)";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ClaimRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Waits until no other claim runs, and keeps the others waiting until this
	 * transaction ends: claims count the runs at once and pick them consistently.
	 */
	void takeTurn() {
		this.jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS turn")
			.param("key", CLAIM_LOCK)
			.query(Integer.class)
			.single();
	}

	/**
	 * The queued runs a claim may take: each workspace's oldest first, then their second
	 * oldest, and so on, no more than the claim's limit from any one workspace.
	 * @param limit the claim's limit
	 * @param exclude runs to leave out
	 * @return the runs, in that order
	 */
	List<Candidate> candidates(int limit, Set<String> exclude) {
		return this.jdbc
			.sql("""
					SELECT ranked.id, ranked.workspace_id
					FROM (SELECT q.id, q.workspace_id, q.created_at,
					          row_number() OVER (PARTITION BY q.workspace_id ORDER BY q.created_at, q.id) AS position
					      FROM simulation_runs AS q
					      WHERE q.status = 'queued' %s) AS ranked
					WHERE ranked.position <= :limit
					ORDER BY ranked.position, ranked.created_at, ranked.id"""
				.formatted(exclude.isEmpty() ? "" : NOT_EXCLUDED))
			.param("limit", limit)
			.param("exclude", exclude)
			.query((row, number) -> new Candidate(row.getString("id"), row.getString("workspace_id")))
			.list();
	}

	/**
	 * Workspaces' kinds and own caps.
	 * @param ids the workspaces
	 * @return each workspace's
	 */
	List<WorkspaceCap> caps(Collection<String> ids) {
		return this.jdbc.sql("SELECT id, kind, max_concurrent_runs FROM workspaces WHERE id IN (:ids)")
			.param("ids", ids)
			.query((row, number) -> new WorkspaceCap(row.getString("id"), row.getString("kind"),
					row.getObject("max_concurrent_runs", Integer.class)))
			.list();
	}

	/**
	 * The runs workspaces have running now, on every worker.
	 * @param ids the workspaces
	 * @return the count of each workspace with any
	 */
	Map<String, Integer> running(Collection<String> ids) {
		return this.jdbc.sql("""
				SELECT workspace_id, count(*) AS running FROM simulation_runs
				WHERE status = 'running' AND workspace_id IN (:ids) GROUP BY workspace_id""")
			.param("ids", ids)
			.query((row, number) -> Map.entry(row.getString("workspace_id"), row.getInt("running")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * Makes queued runs a worker's: running under its lease, started now, one more
	 * attempt counted.
	 * @param runIds the runs
	 * @param workerId the worker
	 * @param now the time
	 * @param leaseEnd when the lease runs out unless renewed
	 */
	void lease(List<String> runIds, String workerId, Instant now, Instant leaseEnd) {
		this.jdbc.sql("""
				UPDATE simulation_runs
				SET status = 'running', lease_owner = :worker, lease_expires_at = :leaseEnd, started_at = :now,
				    progress = 0.0, attempts = attempts + 1
				WHERE id IN (:ids) AND status = 'queued'""")
			.param("worker", workerId)
			.param("leaseEnd", Timestamp.from(leaseEnd))
			.param("now", Timestamp.from(now))
			.param("ids", runIds)
			.update();
	}

	/**
	 * The runs a worker took, with what their simulations need.
	 * @param runIds the runs
	 * @param workerId the worker
	 * @return those it holds
	 */
	List<ClaimedRun> claimed(List<String> runIds, String workerId) {
		return this.jdbc.sql("""
				SELECT run.id, CAST(design.document AS text) AS document, run.scenario_id, run.attempts,
				    run.max_requests, run.max_wall_seconds, run.seed, run.mode
				FROM simulation_runs AS run JOIN run_designs AS design ON design.digest = run.design_digest
				WHERE run.id IN (:ids) AND run.lease_owner = :worker""")
			.param("ids", runIds)
			.param("worker", workerId)
			.query(this::claimedRun)
			.list();
	}

	private ClaimedRun claimedRun(ResultSet row, int number) throws SQLException {
		return new ClaimedRun(row.getString("id"), this.json.readTree(row.getString("document")),
				row.getString("scenario_id"), row.getInt("attempts"), row.getObject("max_requests", Long.class),
				row.getObject("max_wall_seconds", Double.class), row.getLong("seed"), mode(row.getString("mode")));
	}

	// anything but a quick check is a whole run, as the Python worker read it
	private static SimulationMode mode(@Nullable String mode) {
		return "sampled".equals(mode) ? SimulationMode.SAMPLED : SimulationMode.FULL;
	}

	/**
	 * A workspace's kind and its own cap of runs at once.
	 *
	 * @param id the workspace
	 * @param kind its kind
	 * @param maxConcurrentRuns its own cap, or {@code null} for its kind's
	 */
	record WorkspaceCap(String id, String kind, @Nullable Integer maxConcurrentRuns) {

	}

}
