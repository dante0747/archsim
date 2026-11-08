package io.archsim.engine.components.kubernetes;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.kernel.Promise;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.PodRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cluster's nodes, as the scheduler places pods on them: on the least allocated node
 * with room, else Pending until a node has room.
 */
class NodePoolTests {

	/** Two nodes of 2 vCPU and 4 GiB, all of it for pods. */
	private static final String TWO_NODES = """
			{"nodes": 2, "nodeVcpu": 2, "nodeMemoryGib": 4, "allocatableShare": 1}""";

	private final Simulation simulation = new Simulation();

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(10_000, 0, 10_000), OptionalInt.empty());

	@Test
	void request_aNodeWithRoom_isBoundAtOnce() {
		NodePool pool = pool();

		assertThat(pool.request(new PodRequest(1, 1)).triggered()).isTrue();
	}

	@Test
	void request_noNodeWithRoom_waitsPending() {
		NodePool pool = pool();
		pool.request(new PodRequest(2, 1));
		pool.request(new PodRequest(2, 1));

		Promise<ClusterNode> third = pool.request(new PodRequest(2, 1));

		assertThat(third.triggered()).isFalse();
		assertThat(pool.pendingCount()).isOne();
	}

	@Test
	void request_aSecondPod_goesToTheEmptierNode() {
		NodePool pool = pool();
		Promise<ClusterNode> first = pool.request(new PodRequest(1, 1));

		Promise<ClusterNode> second = pool.request(new PodRequest(1, 1));

		assertThat(second.value()).isNotSameAs(first.value());
	}

	@Test
	void request_nodesEquallyAllocated_goesToTheFirst() {
		NodePool pool = pool();

		Promise<ClusterNode> first = pool.request(new PodRequest(1, 1));

		assertThat(first.value()).isSameAs(pool.nodes().getFirst());
	}

	@Test
	void meanWaitMs_podsBoundAtOnce_isNone() {
		NodePool pool = pool();
		pool.request(new PodRequest(1, 1));

		assertThat(pool.meanWaitMs()).isEmpty();
	}

	@Test
	void addProvisioned_nothingPending_addsAnEmptyNode() {
		NodePool pool = pool();
		pool.startProvisioning(0);

		pool.addProvisioned();

		assertThat(pool.nodeCount()).isEqualTo(3);
	}

	@Test
	void addProvisioned_aPendingPodThatFits_bindsIt() {
		NodePool pool = pool();
		pool.request(new PodRequest(2, 1));
		pool.request(new PodRequest(2, 1));
		Promise<ClusterNode> waiting = pool.request(new PodRequest(2, 1));
		pool.startProvisioning(0);

		pool.addProvisioned();

		assertThat(waiting.triggered()).isTrue();
	}

	private NodePool pool() {
		return new NodePool(this.simulation, this.metrics,
				RecordCodec.read((ObjectNode) JsonMapper.builder().build().readTree(TWO_NODES), ClusterConfig.class));
	}

}
