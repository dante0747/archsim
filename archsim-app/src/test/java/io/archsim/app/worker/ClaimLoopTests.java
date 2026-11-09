package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import io.archsim.engine.simulation.SimulationLimits;

import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.atLeast;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * A worker's claims through faults: a claim the database fails, for want of a connection
 * or otherwise, is not the last.
 */
class ClaimLoopTests {

	private static final WorkerContext CONTEXT = new WorkerContext(new WorkerIdentity("a", "host-a", 1), 1,
			SimulationLimits.DEFAULT, QueueParts.SETTINGS, Clock.systemUTC());

	private static final Duration A_WHILE = Duration.ofSeconds(10);

	private final RunClaims claims = mock();

	private final Executions executions = mock();

	private final ClaimLoop loop = new ClaimLoop(this.claims, this.executions, CONTEXT);

	@BeforeEach
	void aFreeSlot() {
		given(this.executions.free()).willReturn(1);
		given(this.executions.ids()).willReturn(Set.of());
	}

	@AfterEach
	void stopTheLoop() {
		this.loop.stop();
	}

	@Test
	void start_aClaimWithoutADatabaseConnection_isTriedAgain() {
		given(this.claims.claim(any(), any())).willThrow(new CannotCreateTransactionException("no connection"))
			.willReturn(List.of());

		this.loop.start();

		await().atMost(A_WHILE).untilAsserted(() -> then(this.claims).should(atLeast(2)).claim(any(), any()));
	}

	@Test
	void start_aClaimThatFailsUnexpectedly_isTriedAgain() {
		given(this.claims.claim(any(), any())).willThrow(new IllegalStateException("a fault")).willReturn(List.of());

		this.loop.start();

		await().atMost(A_WHILE).untilAsserted(() -> then(this.claims).should(atLeast(2)).claim(any(), any()));
	}

}
