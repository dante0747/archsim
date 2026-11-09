package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import io.archsim.engine.run.SimulationLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A claimed run asked to stop: cancelled as someone asked, at once when it stops when
 * asked, after its grace when it does not; failed past its hard deadline; given back when
 * its worker stops; and left alone, nothing written, when another worker took it. Its
 * progress is written at most once a second.
 */
@Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
class ExecutionStopTests {

	private static final Duration A_WHILE = Duration.ofSeconds(10);

	private final QueueFixture queue = new QueueFixture();

	private final ExecutionFixture executions = new ExecutionFixture(this.queue);

	@AfterEach
	void stopTheThreads() {
		this.executions.close();
	}

	@Test
	void requestCancel_aRunThatStopsWhenAsked_isCancelledWellBeforeItsGrace() {
		try (ExecutionFixture patient = new ExecutionFixture(this.queue, Duration.ofMinutes(1))) {
			ClaimedRun run = patient.claimed();
			Execution execution = patient.execution(run, StandIns.untilAskedToStop());
			CompletableFuture<Void> done = execution.start();

			execution.requestCancel();

			done.join();
			assertThat(this.queue.row(run.id()).status()).isEqualTo("cancelled");
		}
	}

	@Test
	void requestCancel_aRunThatWillNotStop_isCancelledAfterItsGraceAndKeepsItsThread() {
		ClaimedRun run = this.executions.claimed();
		CountDownLatch released = new CountDownLatch(1);
		Execution execution = this.executions.execution(run, StandIns.stubborn(released));
		CompletableFuture<Void> done = execution.start();

		execution.requestCancel();

		await().atMost(A_WHILE).until(() -> "cancelled".equals(this.queue.row(run.id()).status()));
		assertThat(done).isNotDone();
		released.countDown();
		done.join();
		assertThat(this.queue.row(run.id()).status()).isEqualTo("cancelled");
	}

	@Test
	void start_aRunPastItsHardDeadline_failsAndIsAskedToStop() {
		ClaimedRun claimed = this.executions.claimed();
		ClaimedRun run = new ClaimedRun(claimed.id(), claimed.document(), null, 1, null, 0.2, 0, claimed.mode());

		this.executions.execution(run, StandIns.untilAskedToStop(), Duration.ZERO, System::nanoTime).start().join();

		assertThat(this.queue.row(run.id()).error()).isEqualTo("{\"message\": \"Simulation exceeded the wall-clock "
				+ "limit of 0 s and was stopped. Reduce RPS, duration, fan-out or model size.\"}");
	}

	@Test
	void abandon_toRequeue_givesTheRunBack() {
		ClaimedRun run = this.executions.claimed();
		Execution execution = this.executions.execution(run, StandIns.untilAskedToStop());
		CompletableFuture<Void> done = execution.start();

		execution.abandon(Execution.Abandoned.REQUEUE);

		done.join();
		assertThat(this.queue.row(run.id()).status()).isEqualTo("queued");
		assertThat(this.queue.row(run.id()).attempts()).isZero();
	}

	@Test
	void progress_aRunAnotherWorkerTookOver_stopsItAndWritesNothing() {
		ClaimedRun run = this.executions.claimed();
		this.queue.takeOver(run.id(), "b");

		this.executions.execution(run, StandIns.untilAskedToStop()).start().join();

		assertThat(this.queue.row(run.id()).status()).isEqualTo("running");
		assertThat(this.queue.row(run.id()).leaseOwner()).isEqualTo("b");
	}

	@Test
	void progress_twiceInASecond_isWrittenOnce() {
		ClaimedRun run = this.executions.claimed();
		RunSimulator twice = (document, request, observer) -> {
			observer.progress(0.25);
			observer.progress(0.5);
			throw new SimulationLimitException("Too many requests.");
		};

		this.executions.execution(run, twice, Duration.ofSeconds(30), () -> 0L).start().join();

		assertThat(this.queue.row(run.id()).progress()).isEqualTo(0.25);
	}

}
