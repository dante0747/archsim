package io.archsim.app.worker;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.awaitility.Awaitility.await;

/**
 * The application started as {@code archsim-app worker}: it serves no HTTP, keeps running
 * until stopped, says when it is ready, beats in {@code job_workers} while it runs and
 * leaves when it stops; it never migrates, and refuses a database another build migrated.
 * Each test starts the application on a database of its own.
 */
@ExtendWith(OutputCaptureExtension.class)
class WorkerRoleTests {

	private static final Duration A_WHILE = Duration.ofSeconds(10);

	@Test
	void start_migratedDatabase_registersTheWorkerAndSaysItIsReady(CapturedOutput output) {
		try (ConfigurableApplicationContext context = startWorker(TestDatabases.migratedJdbcUrl())) {
			assertThat(output).containsPattern("worker \\S+-\\d+-[0-9a-f]{8} takes up to 3 run\\(s\\) at once");
			assertThat(context.getBean(JdbcClient.class)
				.sql("SELECT slots, running, status FROM job_workers")
				.query((row, number) -> row.getInt("slots") + " " + row.getInt("running") + " "
						+ row.getString("status"))
				.single()).isEqualTo("3 0 running");
		}
	}

	@Test
	void start_migratedDatabase_beatsWhileItRuns() {
		try (ConfigurableApplicationContext context = startWorker(TestDatabases.migratedJdbcUrl())) {
			await().atMost(A_WHILE)
				.until(() -> context.getBean(JdbcClient.class)
					.sql("SELECT heartbeat_at > started_at FROM job_workers")
					.query(Boolean.class)
					.single());
		}
	}

	@Test
	void start_migratedDatabase_keepsTheProcessRunning() {
		// the heartbeat's thread is a daemon and nothing serves HTTP:
		// Spring Boot's keep-alive thread is what keeps the JVM running
		ConfigurableApplicationContext context = startWorker(TestDatabases.migratedJdbcUrl());

		List<String> threads = Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter((thread) -> thread.isAlive() && !thread.isDaemon())
			.map(Thread::getName)
			.toList();
		context.close();

		assertThat(threads).contains("keep-alive");
	}

	@Test
	void stop_runningWorker_leavesJobWorkers(CapturedOutput output) {
		String database = TestDatabases.migratedJdbcUrl();

		startWorker(database).close();

		assertThat(jdbc(database).sql("SELECT count(*) FROM job_workers").query(Integer.class).single()).isZero();
		assertThat(output).containsPattern("worker \\S+ stopped");
	}

	@Test
	void start_emptyDatabase_isRefusedUntilTheApiMigratesIt() {
		String database = TestDatabases.freshJdbcUrl();

		assertThatException().isThrownBy(() -> startWorker(database))
			.havingRootCause()
			.withMessage("the database has no schema history yet, this worker needs V3; start the API first");
	}

	@Test
	void start_withoutTheWorkerRole_startsNoWorker() {
		String database = TestDatabases.migratedJdbcUrl();

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ArchsimApplication.class)
			.web(Role.WORKER.webApplicationType())
			.run(settings(database))) {
			assertThat(context.getBeansOfType(Worker.class)).isEmpty();
			assertThat(context.getBeansOfType(WorkerRepository.class)).isEmpty();
			assertThat(jdbc(database).sql("SELECT count(*) FROM job_workers").query(Integer.class).single()).isZero();
		}
	}

	private static ConfigurableApplicationContext startWorker(String database) {
		return new SpringApplicationBuilder(ArchsimApplication.class).web(Role.WORKER.webApplicationType())
			.profiles(Role.WORKER.profile())
			.run(settings(database));
	}

	// three runs at once, a beat every 50 ms, and the worker's own log at INFO, as in
	// production
	private static String[] settings(String database) {
		return new String[] { "--archsim.database-url=" + database, "--archsim.max-concurrent-runs=3",
				"--archsim.heartbeat-seconds=0.05", "--logging.level.io.archsim.app.worker=info" };
	}

	private static JdbcClient jdbc(String database) {
		return JdbcClient.create(new DriverManagerDataSource(database));
	}

}
