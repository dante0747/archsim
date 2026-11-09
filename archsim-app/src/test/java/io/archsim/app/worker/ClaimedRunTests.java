package io.archsim.app.worker;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.SimulationMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits a claimed run simulates under: the worker's, each tightened by the run's own
 * (its workspace's when it was queued), so a workspace's limits never loosen the
 * operator's.
 */
class ClaimedRunTests {

	/** The worker's: two million requests, a minute. */
	private static final SimulationLimits WORKER = new SimulationLimits(2_000_000, 60.0);

	@Test
	void limits_aRunTighterOnRequestsAndTheWorkerOnTime_takeTheTighterOfEach() {
		ClaimedRun run = run(1000L, 300.0);

		assertThat(run.limits(WORKER)).isEqualTo(new SimulationLimits(1000, 60.0));
	}

	@Test
	void limits_aRunWithoutLimitsOfItsOwn_areTheWorkers() {
		ClaimedRun run = run(null, null);

		assertThat(run.limits(WORKER)).isEqualTo(WORKER);
	}

	private static ClaimedRun run(@Nullable Long maxRequests, @Nullable Double maxWallSeconds) {
		return new ClaimedRun("r", QueueParts.JSON.createObjectNode(), null, 1, maxRequests, maxWallSeconds, 7,
				SimulationMode.FULL);
	}

}
