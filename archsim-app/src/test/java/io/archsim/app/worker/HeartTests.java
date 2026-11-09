package io.archsim.app.worker;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * A worker's beat, as the Python worker's: a stopping worker beats as draining and reaps
 * no more, until it starts again; a renewal the database fails still lets it beat.
 */
class HeartTests {

	private static final WorkerContext CONTEXT = new WorkerContext(new WorkerIdentity("a", "host-a", 1), 1,
			SimulationLimits.DEFAULT, QueueParts.SETTINGS, Clock.systemUTC());

	private final LeaseRepository leases = mock();

	private final WorkerRepository registry = mock();

	private final Executions executions = mock();

	private final Heart heart = new Heart(this.leases, this.registry, this.executions, CONTEXT);

	@BeforeEach
	void noRuns() {
		given(this.executions.now()).willReturn(Map.of());
		given(this.executions.ids()).willReturn(Set.of());
		given(this.leases.reap(any())).willReturn(new Reaped(0, 0, 0));
	}

	@Test
	void beatOnce_draining_beatsAsDrainingAndReapsNot() {
		this.heart.drain();

		this.heart.beatOnce();

		then(this.registry).should().beat(argThat(Heartbeat::draining), any());
		then(this.leases).should(never()).reap(any());
	}

	@Test
	void beatOnce_resumedAfterADrain_beatsAsRunningAndReaps() {
		this.heart.drain();
		this.heart.resume();

		this.heart.beatOnce();

		then(this.registry).should().beat(argThat((beat) -> !beat.draining()), any());
		then(this.leases).should().reap(any());
	}

	@Test
	void beatOnce_aRenewalWithoutADatabaseConnection_stillBeats() {
		given(this.executions.now()).willReturn(Map.of("r", mock(Execution.class)));
		given(this.leases.renew(any(), any(), any())).willThrow(new CannotCreateTransactionException("no connection"));

		assertThatNoException().isThrownBy(this.heart::beatOnce);
		then(this.registry).should().beat(any(), any());
	}

}
