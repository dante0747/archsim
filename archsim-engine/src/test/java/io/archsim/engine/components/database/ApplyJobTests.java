package io.archsim.engine.components.database;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;

import static org.assertj.core.api.Assertions.assertThat;

/** A write shipped to replicas, acknowledged once enough of them replayed it. */
class ApplyJobTests {

	private final Simulation simulation = new Simulation();

	@Test
	void acknowledge_fewerReplicasThanNeeded_leavesTheWriteWaiting() {
		Signal acknowledged = this.simulation.event();
		ApplyJob job = new ApplyJob(1, 5.0, 2, acknowledged);

		job.acknowledge();

		assertThat(acknowledged.triggered()).isFalse();
	}

	@Test
	void acknowledge_asManyReplicasAsNeeded_acknowledgesTheWrite() {
		Signal acknowledged = this.simulation.event();
		ApplyJob job = new ApplyJob(1, 5.0, 2, acknowledged);

		job.acknowledge();
		job.acknowledge();

		assertThat(acknowledged.triggered()).isTrue();
	}

}
