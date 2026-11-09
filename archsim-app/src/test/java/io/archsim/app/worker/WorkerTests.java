package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.atLeast;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.mock;

/**
 * A worker's life: a beat that fails is not the last, whatever it fails with; a stop
 * claims no more, gives the runs back while the heartbeat keeps their leases, then
 * leaves; and a stopped worker starts again.
 */
class WorkerTests {

	/** A beat every 10 ms. */
	private static final WorkerContext CONTEXT = new WorkerContext(new WorkerIdentity("a", "host-a", 1), 3,
			SimulationLimits.DEFAULT, new WorkerProperties(30.0, 0.01, 10.0, 10.0), Clock.systemUTC());

	private final Heart heart = mock();

	private final ClaimLoop claims = mock();

	private final Executions executions = mock();

	private final Worker worker = new Worker(this.heart, this.claims, this.executions, CONTEXT);

	@AfterEach
	void stopTheWorker() {
		this.worker.stop();
	}

	@Test
	void start_aBeatFailsUnexpectedly_keepsBeating() {
		willDoNothing().willThrow(new IllegalStateException("a fault")).willDoNothing().given(this.heart).beatOnce();

		this.worker.start();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> then(this.heart).should(atLeast(3)).beatOnce());
	}

	@Test
	void stop_aRunningWorker_claimsNoMoreGivesItsRunsBackThenLeaves() {
		given(this.executions.ids()).willReturn(Set.of("r"));
		this.worker.start();

		this.worker.stop();

		InOrder order = inOrder(this.claims, this.heart, this.executions);
		then(this.claims).should(order).stop();
		then(this.heart).should(order).drain();
		then(this.executions).should(order).giveBackAll();
		then(this.executions).should(order).awaitAll(any());
		then(this.heart).should(order).leave();
	}

	@Test
	void start_afterAStop_runsAgain() {
		this.worker.start();
		this.worker.stop();

		this.worker.start();

		assertThat(this.worker.isRunning()).isTrue();
		then(this.heart).should(times(2)).resume();
	}

}
