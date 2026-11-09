package io.archsim.app.worker;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code job_workers} rows, which say for operators which workers are alive, how many
 * runs each takes and how many it has: one per worker, made at its first beat, renewed at
 * each beat after, removed when it stops. Each test has workers of its own.
 */
class WorkerRepositoryTests {

	private static final JdbcClient JDBC = JdbcClient
		.create(new DriverManagerDataSource(TestDatabases.migratedJdbcUrl()));

	private static final Instant START = Instant.parse("2026-11-09T12:00:00Z");

	private static final Instant LATER = START.plusSeconds(2);

	private final WorkerRepository repository = new WorkerRepository(JDBC);

	@Test
	void beat_newWorker_registersIt() {
		WorkerIdentity worker = worker();

		this.repository.beat(new Heartbeat(worker, 3, 0), START);

		assertThat(row(worker)).isEqualTo(new WorkerRow("host-a", 4242, 3, 0, "running", START, START));
	}

	@Test
	void beat_registeredWorker_renewsItsBeatAndCountsButKeepsItsStart() {
		WorkerIdentity worker = worker();
		this.repository.beat(new Heartbeat(worker, 3, 0), START);

		this.repository.beat(new Heartbeat(worker, 4, 2), LATER);

		assertThat(row(worker)).isEqualTo(new WorkerRow("host-a", 4242, 4, 2, "running", START, LATER));
	}

	@Test
	void forget_registeredWorker_removesIt() {
		WorkerIdentity worker = worker();
		WorkerIdentity other = worker();
		this.repository.beat(new Heartbeat(worker, 3, 0), START);
		this.repository.beat(new Heartbeat(other, 3, 0), START);

		this.repository.forget(worker);

		assertThat(workers(worker)).isZero();
		assertThat(workers(other)).isOne();
	}

	private static WorkerIdentity worker() {
		return new WorkerIdentity("host-a-4242-" + UUID.randomUUID(), "host-a", 4242);
	}

	private static WorkerRow row(WorkerIdentity worker) {
		return JDBC.sql("""
				SELECT hostname, pid, slots, running, status, started_at, heartbeat_at
				FROM job_workers WHERE id = :id""")
			.param("id", worker.id())
			.query((rows, number) -> new WorkerRow(rows.getString("hostname"), rows.getInt("pid"), rows.getInt("slots"),
					rows.getInt("running"), rows.getString("status"),
					rows.getObject("started_at", OffsetDateTime.class).toInstant(),
					rows.getObject("heartbeat_at", OffsetDateTime.class).toInstant()))
			.single();
	}

	private static int workers(WorkerIdentity worker) {
		return JDBC.sql("SELECT count(*) FROM job_workers WHERE id = :id")
			.param("id", worker.id())
			.query(Integer.class)
			.single();
	}

	/**
	 * A worker's row, its times as instants.
	 *
	 * @param hostname its machine
	 * @param pid its process
	 * @param slots the runs it takes at once
	 * @param running the runs it has
	 * @param status {@code running} or {@code draining}
	 * @param startedAt its first beat
	 * @param heartbeatAt its latest beat
	 */
	private record WorkerRow(String hostname, int pid, int slots, int running, String status, Instant startedAt,
			Instant heartbeatAt) {

	}

}
