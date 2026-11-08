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
 * A cluster's autoscaler at its first scan, after a second: the nodes it adds for Pending
 * pods, packed largest first, and those it cannot add.
 */
class ClusterAutoscalerTests {

	/** One node of 2 vCPU and 4 GiB, all of it for pods, scanned every second. */
	private static final String ONE_NODE = """
			{"nodes": 1, "nodeVcpu": 2, "nodeMemoryGib": 4, "allocatableShare": 1,
			 "maxNodes": 5, "scanIntervalS": 1}""";

	private final Simulation simulation = new Simulation();

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(10_000, 0, 10_000), OptionalInt.empty());

	@Test
	void scan_aPodNoNodeHasTheCpuFor_addsNoNode() {
		NodePool pool = startedOn(oneNode());
		pool.request("svc", new PodRequest(3, 1));

		scanOnce();

		assertThat(pool.provisioningCount()).isZero();
	}

	@Test
	void scan_aPodNoNodeHasTheMemoryFor_addsNoNode() {
		NodePool pool = startedOn(oneNode());
		pool.request("svc", new PodRequest(1, 5));

		scanOnce();

		assertThat(pool.provisioningCount()).isZero();
	}

	@Test
	void scan_twoPodsTooBigForOneNodeTogether_addsTwo() {
		NodePool pool = startedOn(oneNode());
		pool.request("svc", new PodRequest(2, 4));
		pool.request("svc", new PodRequest(1, 3));
		pool.request("svc", new PodRequest(1, 3));

		scanOnce();

		assertThat(pool.provisioningCount()).isEqualTo(2);
	}

	@Test
	void scan_podsThatPackLargestFirst_addTheFewestNodes() {
		NodePool pool = startedOn(oneNode());
		pool.request("svc", new PodRequest(2, 4));
		pool.request("svc", new PodRequest(0.5, 1));
		pool.request("svc", new PodRequest(0.5, 1));
		pool.request("svc", new PodRequest(1.5, 1));
		pool.request("svc", new PodRequest(1.5, 1));

		scanOnce();

		assertThat(pool.provisioningCount()).isEqualTo(2);
	}

	@Test
	void scan_aNodeOnItsWay_addsNoMoreForTheSamePod() {
		NodePool pool = startedOn(oneNode());
		pool.request("svc", new PodRequest(2, 4));
		pool.request("svc", new PodRequest(1, 1));

		this.simulation.runUntil(2_500, 1, () -> {
		});

		assertThat(pool.provisioningCount()).isOne();
	}

	@Test
	void scan_noProvisioningTime_bindsThePendingPodAtTheScan() {
		NodePool pool = startedOn(oneNode().put("nodeProvisionS", 0));
		pool.request("svc", new PodRequest(2, 4));
		Promise<Optional<ClusterNode>> waiting = pool.request("svc", new PodRequest(1, 1));

		scanOnce();

		assertThat(waiting.triggered()).isTrue();
	}

	private static ObjectNode oneNode() {
		return (ObjectNode) JsonMapper.builder().build().readTree(ONE_NODE);
	}

	private NodePool startedOn(ObjectNode settings) {
		ClusterConfig config = RecordCodec.read(settings, ClusterConfig.class);
		NodePool pool = new NodePool(this.simulation, this.metrics, config);
		new ClusterAutoscaler(this.simulation, pool, config, this.metrics).start();
		return pool;
	}

	private void scanOnce() {
		this.simulation.runUntil(1_500, 1, () -> {
		});
	}

}
