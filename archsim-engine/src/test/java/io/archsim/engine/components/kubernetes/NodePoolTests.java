package io.archsim.engine.components.kubernetes;

import java.util.Optional;
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

		assertThat(pool.request("svc", new PodRequest(1, 1)).triggered()).isTrue();
	}

	@Test
	void request_noNodeWithRoom_waitsPending() {
		NodePool pool = pool();
		pool.request("svc", new PodRequest(2, 1));
		pool.request("svc", new PodRequest(2, 1));

		Promise<Optional<ClusterNode>> third = pool.request("svc", new PodRequest(2, 1));

		assertThat(third.triggered()).isFalse();
		assertThat(pool.pendingCount()).isOne();
	}

	@Test
	void request_aSecondPod_goesToTheEmptierNode() {
		NodePool pool = pool();
		Promise<Optional<ClusterNode>> first = pool.request("svc", new PodRequest(1, 1));

		Promise<Optional<ClusterNode>> second = pool.request("svc", new PodRequest(1, 1));

		assertThat(second.value().orElseThrow()).isNotSameAs(first.value().orElseThrow());
	}

	@Test
	void request_nodesEquallyAllocated_goesToTheFirst() {
		NodePool pool = pool();

		Promise<Optional<ClusterNode>> first = pool.request("svc", new PodRequest(1, 1));

		assertThat(first.value().orElseThrow()).isSameAs(pool.nodes().getFirst());
	}

	@Test
	void meanWaitMs_podsBoundAtOnce_isNone() {
		NodePool pool = pool();
		pool.request("svc", new PodRequest(1, 1));

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
		pool.request("svc", new PodRequest(2, 1));
		pool.request("svc", new PodRequest(2, 1));
		Promise<Optional<ClusterNode>> waiting = pool.request("svc", new PodRequest(2, 1));
		pool.startProvisioning(0);

		pool.addProvisioned();

		assertThat(waiting.triggered()).isTrue();
	}

	@Test
	void release_aPodOnAFullNode_bindsAPendingPodThatFitsNow() {
		NodePool pool = pool();
		Promise<Optional<ClusterNode>> first = pool.request("svc", new PodRequest(2, 1));
		pool.request("svc", new PodRequest(2, 1));
		Promise<Optional<ClusterNode>> waiting = pool.request("svc", new PodRequest(2, 1));

		pool.release(first.value().orElseThrow(), new PodRequest(2, 1));

		assertThat(waiting.triggered()).isTrue();
	}

	@Test
	void cancelPending_aServicesPendingPods_deletesTheNewestFirst() {
		NodePool pool = pool();
		pool.request("full", new PodRequest(2, 1));
		pool.request("full", new PodRequest(2, 1));
		Promise<Optional<ClusterNode>> older = pool.request("svc", new PodRequest(1, 1));
		Promise<Optional<ClusterNode>> newer = pool.request("svc", new PodRequest(1, 1));

		pool.cancelPending("svc", 1);

		assertThat(older.triggered()).isFalse();
		assertThat(newer.value()).isEmpty();
	}

	@Test
	void cancelPending_anotherServicesPendingPod_keepsIt() {
		NodePool pool = pool();
		pool.request("full", new PodRequest(2, 1));
		pool.request("full", new PodRequest(2, 1));
		Promise<Optional<ClusterNode>> other = pool.request("other", new PodRequest(1, 1));

		pool.cancelPending("svc", 1);

		assertThat(other.triggered()).isFalse();
	}

	private NodePool pool() {
		return new NodePool(this.simulation, this.metrics,
				RecordCodec.read((ObjectNode) JsonMapper.builder().build().readTree(TWO_NODES), ClusterConfig.class));
	}

}
