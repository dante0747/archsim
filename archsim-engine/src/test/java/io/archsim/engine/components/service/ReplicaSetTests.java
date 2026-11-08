package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.FullCores;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.WorkerPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * An autoscaling service's replicas: started ones join, the newest leaves to scale down
 * but never the last, and every change is a new version.
 */
class ReplicaSetTests {

	private final Simulation simulation = new Simulation();

	private final Timeline timeline = new Timeline(1000, 0, 1000);

	@Test
	void start_aReplica_takesRequestsAsANewVersion() {
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present(1));

		replicas.scaling().orElseThrow().start(replica(10), 10);

		assertThat(replicas.routableCount()).isEqualTo(2);
		assertThat(replicas.version()).isOne();
	}

	@Test
	void retireNewest_theLastReplica_keepsIt() {
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present(1));

		replicas.scaling().orElseThrow().retireNewest(10);

		assertThat(replicas.routableCount()).isOne();
		assertThat(replicas.version()).isZero();
	}

	@Test
	void retireNewest_twoReplicas_stopsSendingToTheNewest() {
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present(1));
		Replica started = replica(10);
		replicas.scaling().orElseThrow().start(started, 10);

		replicas.scaling().orElseThrow().retireNewest(20);

		assertThat(replicas.routed(1)).isNotSameAs(started);
		assertThat(replicas.version()).isEqualTo(2);
	}

	@Test
	void countSeries_aRunEndingInsideABucket_averagesOverWhatItSimulated() {
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present(2));
		int bucketMs = this.timeline.bucketMs();

		replicas.finish(bucketMs * 1.5);

		assertThat(replicas.scaling().orElseThrow().countSeries().subList(0, 3)).containsExactly(2.0, 2.0, null);
	}

	@Test
	void withdraw_aReplicaWhosePodIsPending_stopsSendingToItAsANewVersion() {
		List<Replica> present = present(2);
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present);

		replicas.scaling().orElseThrow().withdraw(present.getFirst(), 0);

		assertThat(replicas.routableReplicas()).containsExactly(present.get(1));
		assertThat(replicas.version()).isOne();
	}

	@Test
	void rejoin_aWithdrawnReplica_takesTurnsAfterTheOthers() {
		List<Replica> present = present(2);
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present);
		replicas.scaling().orElseThrow().withdraw(present.getFirst(), 0);

		replicas.scaling().orElseThrow().rejoin(present.getFirst(), 10);

		assertThat(replicas.routableReplicas()).containsExactly(present.get(1), present.getFirst());
	}

	@Test
	void withdraw_aReplicaTakingNoRequests_isRefused() {
		List<Replica> present = present(2);
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present);
		replicas.scaling().orElseThrow().withdraw(present.getFirst(), 0);

		assertThatIllegalStateException()
			.isThrownBy(() -> replicas.scaling().orElseThrow().withdraw(present.getFirst(), 10))
			.withMessage("a replica that takes no requests cannot be withdrawn");
	}

	@Test
	void rejoin_aReplicaTakingRequests_isRefused() {
		List<Replica> present = present(2);
		ReplicaSet replicas = ReplicaSet.changing(this.timeline, 4, present);

		assertThatIllegalStateException()
			.isThrownBy(() -> replicas.scaling().orElseThrow().rejoin(present.getFirst(), 10))
			.withMessage("a replica that takes requests cannot rejoin");
	}

	private List<Replica> present(int count) {
		List<Replica> replicas = new ArrayList<>();
		for (int replica = 0; replica < count; replica++) {
			replicas.add(replica(0));
		}
		return replicas;
	}

	private Replica replica(double born) {
		NodeMetrics metrics = new NodeMetrics(this.timeline, OptionalInt.empty());
		WorkerPool pool = new WorkerPool(this.simulation, 4, new WorkerPool.Queue(10, null), metrics);
		Cores cores = new Cores(this.simulation, null, 4, new Cores.Accounts(null, FullCores.INSTANCE));
		return new Replica(pool, cores, null, born);
	}

}
