package io.archsim.app.worker;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code job_workers} rows, which tell operators which workers are alive: one per
 * worker, made at its first beat, renewed at each beat after and removed when it stops.
 * Nothing reads them to decide anything; a lease says which worker has a run.
 */
@Repository
@Profile("worker")
class WorkerRepository {

	private final JdbcClient jdbc;

	WorkerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Says a worker is alive: registers it at its first beat, renews its beat and its
	 * counts after.
	 * @param heartbeat the worker and its counts
	 * @param now the time of the beat
	 */
	void beat(Heartbeat heartbeat, Instant now) {
		WorkerIdentity worker = heartbeat.worker();
		this.jdbc.sql("""
				INSERT INTO job_workers (id, hostname, pid, slots, running, status, started_at, heartbeat_at)
				VALUES (:id, :hostname, :pid, :slots, :running, 'running', :now, :now)
				ON CONFLICT (id) DO UPDATE
				SET heartbeat_at = :now, running = :running, slots = :slots, status = 'running'""")
			.param("id", worker.id())
			.param("hostname", worker.hostname())
			.param("pid", worker.pid())
			.param("slots", heartbeat.slots())
			.param("running", heartbeat.running())
			.param("now", Timestamp.from(now))
			.update();
	}

	/**
	 * Removes a worker that stops.
	 * @param worker the worker
	 */
	void forget(WorkerIdentity worker) {
		this.jdbc.sql("DELETE FROM job_workers WHERE id = :id").param("id", worker.id()).update();
	}

}
