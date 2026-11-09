package io.archsim.app.worker;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A worker at work, as the Python worker's tests had it: it simulates the runs it claims,
 * no more at once than its slots; its heartbeat passes on a cancel asked for in the
 * database and stops a run another worker took, writing nothing of it; a stop gives its
 * runs back; and at start it settles the runs a worker that died left.
 */
@Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
class WorkerLoopTests {

	private static final Duration A_WHILE = Duration.ofSeconds(20);

	private final QueueFixture queue = new QueueFixture();

	@Test
	void start_queuedRuns_simulatesEachAndWritesItsOutcome() {
		List<String> runs = this.queue.queue("ws", 3);

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.reporting(), 2)) {
			worker.worker().start();

			await().atMost(A_WHILE).until(() -> runs.stream().allMatch((run) -> status(run).equals("succeeded")));
		}
	}

	@Test
	void start_moreRunsThanSlots_takesNoMoreThanItsSlotsAtOnce() {
		List<String> runs = this.queue.queue("ws", 3);
		CountDownLatch released = new CountDownLatch(1);

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.stubborn(released), 2)) {
			worker.worker().start();

			await().atMost(A_WHILE).until(() -> worker.executions().ids().size() == 2);
			await().during(Duration.ofMillis(300)).until(() -> status(runs.get(2)).equals("queued"));
			released.countDown();
			await().atMost(A_WHILE).until(() -> runs.stream().allMatch((run) -> status(run).equals("succeeded")));
		}
	}

	@Test
	void heartbeat_aCancelAskedForInTheDatabase_stopsTheRun() {
		String run = this.queue.queue("ws", 1).getFirst();

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.untilAskedToStop(), 1)) {
			worker.worker().start();
			await().atMost(A_WHILE).until(() -> worker.executions().ids().contains(run));

			this.queue.requestCancel(run, ExecutionFixture.NOW);

			await().atMost(A_WHILE).until(() -> status(run).equals("cancelled"));
		}
	}

	@Test
	void heartbeat_aRunAnotherWorkerTookOver_stopsItAndWritesNothing() {
		String run = this.queue.queue("ws", 1).getFirst();

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.quietUntilAskedToStop(), 1)) {
			worker.worker().start();
			await().atMost(A_WHILE).until(() -> worker.executions().ids().contains(run));

			this.queue.takeOver(run, "w2");

			await().atMost(A_WHILE).until(() -> worker.executions().ids().isEmpty());
			assertThat(this.queue.row(run).status()).isEqualTo("running");
			assertThat(this.queue.row(run).leaseOwner()).isEqualTo("w2");
		}
	}

	@Test
	void stop_runsInFlight_givesThemBackAndLeaves() {
		String run = this.queue.queue("ws", 1).getFirst();

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.untilAskedToStop(), 1)) {
			worker.worker().start();
			await().atMost(A_WHILE).until(() -> worker.executions().ids().contains(run));

			worker.worker().stop();

			assertThat(this.queue.row(run).status()).isEqualTo("queued");
			assertThat(this.queue.row(run).attempts()).isZero();
			assertThat(this.queue.jdbc().sql("SELECT count(*) FROM job_workers").query(Integer.class).single())
				.isZero();
		}
	}

	@Test
	void start_aRunAWorkerThatDiedLeft_settlesItAndTakesItAgain() {
		String run = this.queue.queue("ws", 1).getFirst();
		QueueParts.claims(this.queue, "rate-limits=false")
			.claim(new ClaimRequest("dead", 1, Set.of()), ExecutionFixture.NOW);
		this.queue.lease(run, ExecutionFixture.NOW);

		try (WorkerFixture worker = new WorkerFixture(this.queue, StandIns.reporting(), 1)) {
			worker.worker().start();

			await().atMost(A_WHILE).until(() -> status(run).equals("succeeded"));
			assertThat(this.queue.row(run).attempts()).isEqualTo(2);
		}
	}

	private String status(String run) {
		return this.queue.row(run).status();
	}

}
