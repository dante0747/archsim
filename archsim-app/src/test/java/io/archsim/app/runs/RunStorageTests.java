package io.archsim.app.runs;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.support.FullWorkspaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A workspace that stores as much as its kind may starts no run, which would store its
 * design and its report, until something is deleted (ADR-0109). Runs are queued with the
 * workspace locked, so a start checks again what a write that held the lock stored.
 */
class RunStorageTests extends RunTestSupport {

	private static final Duration A_WHILE = Duration.ofSeconds(30);

	@Test
	void start_inASandboxStoringItsQuota_isAConflictAndQueuesNothing() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);
		FullWorkspaces.fillSandbox(jdbc(), project);

		MvcTestResult refused = start(project, "{}");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
		assertThat(runCount(project)).isZero();
	}

	@Test
	void start_inASandboxBelowItsQuota_isQueued() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void start_whileAnotherWriteFillsTheSandbox_isAConflictAndQueuesNothing() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);

		MvcTestResult refused = startWhileAnotherWriteFills(project);

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
		assertThat(runCount(project)).isZero();
	}

	/**
	 * Starts a run while another transaction holds the project's workspace, and fills it
	 * once the start waits for the workspace, before it lets go.
	 * @param project the project
	 * @return the answer to the start
	 */
	private MvcTestResult startWhileAnotherWriteFills(String project) {
		DataSource other = anotherDataSource();
		TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(other));
		CompletableFuture<MvcTestResult> started = transaction
			.execute((status) -> fillWhileStarting(JdbcClient.create(other), project));
		return Objects.requireNonNull(started).join();
	}

	private CompletableFuture<MvcTestResult> fillWhileStarting(JdbcClient other, String project) {
		other
			.sql("SELECT w.id FROM workspaces w JOIN projects p ON p.workspace_id = w.id "
					+ "WHERE p.id = :id FOR NO KEY UPDATE OF w")
			.param("id", project)
			.query(String.class)
			.single();
		int holder = other.sql("SELECT pg_backend_pid()").query(Integer.class).single();
		CompletableFuture<MvcTestResult> started = CompletableFuture.supplyAsync(() -> start(project, "{}"));
		await().atMost(A_WHILE).until(() -> started.isDone() || waitsFor(holder));
		FullWorkspaces.fillSandbox(other, project);
		return started;
	}

	/**
	 * Whether a connection waits for a lock another one holds.
	 * @param holder the backend that holds it
	 * @return {@code true} while one waits
	 */
	private boolean waitsFor(int holder) {
		return jdbc().sql("SELECT count(*) FROM pg_stat_activity WHERE :holder = ANY(pg_blocking_pids(pid))")
			.param("holder", holder)
			.query(Integer.class)
			.single() > 0;
	}

}
