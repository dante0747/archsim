package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;

import io.archsim.engine.host.SiblingSpeed;
import io.archsim.engine.host.VcpuBasis;

import static org.assertj.core.api.Assertions.assertThat;

/** How a sized part's summary says its vCPUs counted (ADR-0091). */
class VcpuSummaryTests {

	@Test
	void of_pairsOnTheDefault_sayWhatAPairDoesAndWhy() {
		assertThat(VcpuSummary.of(new SiblingSpeed(0.6, VcpuBasis.DEFAULT))).isEqualTo(new VcpuSummary(0.6, "default",
				"hyperthread pairs, each vCPU doing 0.6 of a core's work while its sibling is busy too: the default, "
						+ "as on most x86 instances"));
	}

	@Test
	void of_pairsAtHalfSpeed_sayWhatEachVcpuDoes() {
		assertThat(VcpuSummary.of(new SiblingSpeed(0.5, VcpuBasis.DESIGN)).text())
			.startsWith("hyperthread pairs, each vCPU doing 0.5 of a core's work while its sibling is busy too: "
					+ "set on the design");
	}

	@Test
	void of_wholeCoresOfAFamily_sayWhy() {
		assertThat(VcpuSummary.of(new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY)).text())
			.isEqualTo("whole cores: its instance family's vCPUs are whole cores");
	}

	@Test
	void of_wholeCoresTheDesignSets_sayItSetsThem() {
		assertThat(VcpuSummary.of(new SiblingSpeed(1.0, VcpuBasis.DESIGN)).text())
			.isEqualTo("whole cores: set on the design (architecture.siblingSpeed)");
	}

	@Test
	void of_pods_sayItIsOptimistic() {
		assertThat(VcpuSummary.of(new SiblingSpeed(1.0, VcpuBasis.POD)).text())
			.isEqualTo("whole cores: it runs as pods on Kubernetes, which is optimistic on an x86 node pool");
	}

}
