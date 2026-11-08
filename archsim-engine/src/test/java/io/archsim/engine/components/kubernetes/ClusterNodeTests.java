package io.archsim.engine.components.kubernetes;

import org.junit.jupiter.api.Test;

import io.archsim.model.component.kubernetes.PodRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** A cluster's node: what fits on it, how much it has left, and the images it has. */
class ClusterNodeTests {

	@Test
	void fits_enoughCpuButTooLittleMemory_isFalse() {
		ClusterNode node = new ClusterNode(2, 4, 0);

		assertThat(node.fits(new PodRequest(1, 5))).isFalse();
	}

	@Test
	void freeShare_halfItsCpuReserved_isThreeQuarters() {
		ClusterNode node = new ClusterNode(2, 4, 0);
		node.bind(new PodRequest(1, 0));

		assertThat(node.freeShare()).isEqualTo(0.75);
	}

	@Test
	void emptyFor_aNodeWithAPod_isFalse() {
		ClusterNode node = new ClusterNode(2, 4, 0);
		node.bind(new PodRequest(1, 1));

		assertThat(node.emptyFor(1_000, 10)).isFalse();
	}

	@Test
	void cacheImage_aSecondTime_needsNoPull() {
		ClusterNode node = new ClusterNode(2, 4, 0);
		node.cacheImage("svc");

		assertThat(node.cacheImage("svc")).isFalse();
	}

}
