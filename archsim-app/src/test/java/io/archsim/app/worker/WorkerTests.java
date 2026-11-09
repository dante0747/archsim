package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.atLeast;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * A worker's heartbeat against a database that fails: a beat that fails is not the last,
 * whatever it fails with, and a worker that cannot leave {@code job_workers} still stops.
 */
class WorkerTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

	// a beat every 10 ms
	private static final WorkerProperties OFTEN = new WorkerProperties(30.0, 0.01, 10.0, 10.0);

	private static final Duration A_WHILE = Duration.ofSeconds(10);

	private final WorkerRepository repository = mock();

	private final Worker worker = new Worker(this.repository, NOON, TestProperties.with("max-concurrent-runs=3"),
			OFTEN);

	@AfterEach
	void stopTheWorker() {
		this.worker.stop();
	}

	@Test
	void start_aBeatFailsUnexpectedly_keepsBeating() {
		willDoNothing().willThrow(new IllegalStateException("a fault"))
			.willDoNothing()
			.given(this.repository)
			.beat(any(), any());

		this.worker.start();

		await().atMost(A_WHILE).untilAsserted(() -> then(this.repository).should(atLeast(3)).beat(any(), any()));
	}

	@Test
	void stop_databaseUnreachable_stopsWithoutThrowing() {
		willThrow(new DataAccessResourceFailureException("the database is unreachable")).given(this.repository)
			.forget(any());
		this.worker.start();

		assertThatNoException().isThrownBy(this.worker::stop);
	}

	@Test
	void start_afterAStop_runsAgain() {
		this.worker.start();
		this.worker.stop();

		this.worker.start();

		assertThat(this.worker.isRunning()).isTrue();
	}

}
