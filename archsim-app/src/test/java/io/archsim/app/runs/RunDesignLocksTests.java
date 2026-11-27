package io.archsim.app.runs;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.database.TestDatabases;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.engine.run.QuickCheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * A run queued with a stored design while the design's last other run is deleted:
 * whichever of the two takes the design's row first, the other waits for it, neither
 * fails, and the design stays while a run refers to it.
 */
class RunDesignLocksTests {

	/** Longer than any of these statements takes, once nothing holds it up. */
	private static final Duration A_WHILE = Duration.ofSeconds(30);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ObjectNode DESIGN = (ObjectNode) JSON.readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Shop"}}""");

	private static final NewRun RUN = new NewRun(new Destination("p1", "w1", new WorkspaceLimits.RunLimits(1000, 20.0)),
			new PlannedRun(null, null, 7, DESIGN, new QuickCheck(true, "simulates 30 s", 60.0, null, null)), "Baseline",
			RunMode.FULL);

	private final String url = TestDatabases.migratedJdbcUrl();

	private final DataSource database = new DriverManagerDataSource(this.url);

	/** A connection of its own, outside the transactions, which watches them. */
	private final JdbcClient watcher = JdbcClient.create(new DriverManagerDataSource(this.url));

	private final RunRepository runs = new RunRepository(JdbcClient.create(this.database), JSON);

	private final TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(this.database));

	@BeforeEach
	void aProject() {
		this.watcher.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES ('w1', 'Team', 'team', now())")
			.update();
		this.watcher.sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES ('p1', 'w1', 'Shop', '{}', '1.7', 1, now(), now())""").update();
	}

	@Test
	void delete_lastOtherRunOfADesignBeingQueuedAgain_keepsTheDesign() {
		queue("older");

		CompletableFuture<Void> deleting = meanwhile(() -> queue("newer"), () -> this.runs.delete("older"));

		assertThat(deleting).succeedsWithin(A_WHILE);
		assertThat(this.runs.inputDocument("newer")).isEqualTo(DESIGN);
	}

	@Test
	void add_runOfADesignWhoseLastRunIsBeingDeleted_storesTheDesignAgain() {
		queue("older");

		CompletableFuture<Void> queuing = meanwhile(() -> this.runs.delete("older"), () -> queue("newer"));

		assertThat(queuing).succeedsWithin(A_WHILE);
		assertThat(this.runs.inputDocument("newer")).isEqualTo(DESIGN);
	}

	private void queue(String runId) {
		this.runs.add(new QueuedRun(runId, RUN, "local", null, Instant.parse("2026-11-27T09:00:00Z")));
	}

	/**
	 * Does one thing in a transaction and, while the transaction is open, starts another
	 * on a connection of its own; ends the transaction once something waits for it.
	 * @param first what the transaction does
	 * @param second what is done meanwhile
	 * @return the second, which goes on once the transaction has ended
	 */
	private CompletableFuture<Void> meanwhile(Runnable first, Runnable second) {
		CompletableFuture<Void> started = this.transactions.execute((status) -> {
			first.run();
			CompletableFuture<Void> other = CompletableFuture.runAsync(second);
			await().atMost(A_WHILE).until(this::aStatementWaitsForALock);
			return other;
		});
		if (started == null) {
			return fail("the transaction returned nothing");
		}
		return started;
	}

	// read on a connection of its own: a transaction sees the same activity until it ends
	private boolean aStatementWaitsForALock() {
		return this.watcher.sql(
				"SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
			.query(Integer.class)
			.single() > 0;
	}

}
