package io.archsim.app.calibration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

/**
 * A calibration's replays on platform threads of their own (ADR-0054): never on the
 * virtual thread of the request that asks, its failures in their own words, and no more
 * waiting for a thread than there are threads (ADR-0108).
 */
class ReplayPoolTests {

	private static final Duration A_WHILE = Duration.ofSeconds(10);

	private static final ObjectNode DESIGN = JsonNodeFactory.instance.objectNode().put("kind", "archsim.architecture");

	private static final JsonNode REPORT = JsonNodeFactory.instance.objectNode().put("replayed", true);

	@Test
	void simulate_askedFromAVirtualThread_runsOnAPlatformThreadOfThePool() throws InterruptedException {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		AtomicReference<JsonNode> answer = new AtomicReference<>();

		try (ReplayPool pool = new ReplayPool((document, limits) -> {
			ranOn.set(Thread.currentThread());
			return REPORT;
		}, 1)) {
			Thread.ofVirtual().start(() -> answer.set(pool.simulate(DESIGN, null))).join(A_WHILE);
		}

		assertThat(answer.get()).isSameAs(REPORT);
		assertThat(ranOn.get().isVirtual()).isFalse();
		assertThat(ranOn.get().getName()).isEqualTo("archsim-replay-1");
	}

	@Test
	void simulate_aReplayOverItsLimits_throwsTheSimulatorsException() {
		SimulationLimitException over = new SimulationLimitException("the run would generate too many requests");

		try (ReplayPool pool = new ReplayPool((document, limits) -> {
			throw over;
		}, 1)) {
			assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> pool.simulate(DESIGN, null))
				.isSameAs(over);
		}
	}

	@Test
	void simulate_aDesignTheEngineCannotRead_throwsItsIssues() {
		DocumentInvalidException invalid = new DocumentInvalidException(List.of(Issue.error("document.schema", "bad")));

		try (ReplayPool pool = new ReplayPool((document, limits) -> {
			throw invalid;
		}, 1)) {
			assertThatExceptionOfType(DocumentInvalidException.class).isThrownBy(() -> pool.simulate(DESIGN, null))
				.isSameAs(invalid);
		}
	}

	@Test
	void simulate_interruptedWhileItWaits_keepsTheInterruptAndIsUnavailable() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch never = new CountDownLatch(1);
		AtomicReference<ApiException> refused = new AtomicReference<>();
		AtomicReference<Boolean> interrupted = new AtomicReference<>();

		try (ReplayPool pool = new ReplayPool((document, limits) -> {
			started.countDown();
			return waitFor(never);
		}, 1)) {
			Thread asker = Thread.ofPlatform().start(() -> {
				try {
					pool.simulate(DESIGN, null);
				}
				catch (ApiException ex) {
					refused.set(ex);
					interrupted.set(Thread.currentThread().isInterrupted());
				}
			});
			assertThat(started.await(A_WHILE.toSeconds(), TimeUnit.SECONDS)).isTrue();
			asker.interrupt();
			asker.join(A_WHILE);
		}

		assertThat(refused.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(interrupted.get()).isTrue();
	}

	@Test
	@Timeout(value = 20, threadMode = ThreadMode.SEPARATE_THREAD)
	void simulate_everyThreadBusyAndAsManyReplaysWaiting_isRefusedWithRetryAfter() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);

		try (ReplayPool pool = new ReplayPool((document, limits) -> {
			started.countDown();
			return waitFor(done);
		}, 1)) {
			Thread.ofVirtual().start(() -> pool.simulate(DESIGN, null));
			assertThat(started.await(A_WHILE.toSeconds(), TimeUnit.SECONDS)).isTrue();
			Thread waiting = Thread.ofVirtual().start(() -> pool.simulate(DESIGN, null));
			await().atMost(A_WHILE).until(() -> waiting.getState() == Thread.State.WAITING);
			try {
				assertThatExceptionOfType(ApiException.class).isThrownBy(() -> pool.simulate(DESIGN, null))
					.satisfies((ex) -> {
						assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
						assertThat(ex.headers().getFirst("Retry-After")).isEqualTo("10");
					})
					.withMessage("The server is replaying other calibrations: calibrate again in a moment");
			}
			finally {
				done.countDown();
			}
		}
	}

	private static JsonNode waitFor(CountDownLatch latch) {
		try {
			latch.await();
			return REPORT;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("the replay was stopped", ex);
		}
	}

}
