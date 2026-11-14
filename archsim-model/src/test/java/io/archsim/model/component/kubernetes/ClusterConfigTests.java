package io.archsim.model.component.kubernetes;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;

/** The nodes a cluster runs for its pods once the load holds. */
class ClusterConfigTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Each node allocates 1.8 vCPU and 7.2 GiB to pods. */
	private static final String AUTOSCALED = "{\"nodes\": 2, \"nodeVcpu\": 2, \"nodeMemoryGib\": 8, "
			+ "\"clusterAutoscaler\": true, \"minNodes\": 2, \"maxNodes\": 5}";

	private static final PodRequest HALF_A_VCPU = new PodRequest(0.5, 1);

	@Test
	void nodesFor_podsThatFitFewerNodesThanTheMinimum_keepTheMinimum() {
		assertThat(cluster(AUTOSCALED).nodesFor(pods(3, HALF_A_VCPU))).isEqualTo(2);
	}

	@Test
	void nodesFor_morePodsThanTheMinimumHolds_getTheNodesTheyNeed() {
		assertThat(cluster(AUTOSCALED).nodesFor(pods(10, HALF_A_VCPU))).isEqualTo(4);
	}

	@Test
	void nodesFor_morePodsThanTheMaximumHolds_stopAtTheMaximum() {
		assertThat(cluster(AUTOSCALED).nodesFor(pods(30, HALF_A_VCPU))).isEqualTo(5);
	}

	@Test
	void nodesFor_podsOfTwoSizes_packTheLargestFirst() {
		PodRequest small = new PodRequest(0.6, 1);
		PodRequest large = new PodRequest(1.2, 1);

		// in the order given, the two small pods would share a node and each large one
		// take its own
		assertThat(cluster(AUTOSCALED.replace("\"minNodes\": 2", "\"minNodes\": 1"))
			.nodesFor(List.of(small, small, large, large))).isEqualTo(2);
	}

	@Test
	void nodesFor_aPodNoNodeHolds_addsNoNodeForIt() {
		assertThat(cluster(AUTOSCALED.replace("\"minNodes\": 2", "\"minNodes\": 1"))
			.nodesFor(List.of(new PodRequest(4, 1)))).isEqualTo(1);
	}

	@Test
	void nodesFor_withoutTheAutoscaler_isItsNodes() {
		assertThat(cluster(AUTOSCALED.replace("\"clusterAutoscaler\": true", "\"clusterAutoscaler\": false"))
			.nodesFor(pods(30, HALF_A_VCPU))).isEqualTo(2);
	}

	private static List<PodRequest> pods(int count, PodRequest request) {
		return Collections.nCopies(count, request);
	}

	private static ClusterConfig cluster(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), ClusterConfig.class);
	}

}
