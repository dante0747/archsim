package io.archsim.engine.sizing;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** What a part needs against what its instance has, in words. */
class NeedTextTests {

	private static final Map<String, SpecValue> EIGHT_VCPU = Map.of(Spec.CPU_CORES.json(),
			new SpecValue(JsonNodeFactory.instance.numberNode(8.0), SpecSource.AUTO));

	@Test
	void of_aCpuNeedOnWholeCores_isItsBusyCoresAtTheTarget() {
		Map<String, Double> need = Map.of(Need.BUSY_CORES.json(), 2.4, Need.CPU_CORES.json(), 2.4 / Sizing.TARGET_CPU);

		assertThat(NeedText.of(need, EIGHT_VCPU, "each replica", null)).containsEntry(Spec.CPU_CORES.json(),
				"needs 4 vCPU (2.4 busy at the peak, at a 60% target) · each replica has 8 vCPU");
	}

	@Test
	void of_aCpuNeedOnHyperthreads_saysWhatItsHyperthreadsCanDo() {
		Map<String, Double> need = Map.of(Need.BUSY_CORES.json(), 2.4, Need.CPU_CORES.json(),
				2.4 / Sizing.TARGET_CPU / 0.6);

		assertThat(NeedText.of(need, EIGHT_VCPU, "each replica", 0.6)).containsEntry(Spec.CPU_CORES.json(),
				"needs 6.67 vCPU (2.4 busy at the peak, at a 60% target of what its hyperthreads can do, 0.6 of a "
						+ "core each) · each replica has 8 vCPU");
	}

	@Test
	void of_aCpuNeedOnWholeCoresRoundedAboveItsBusyCoresAtTheTarget_isStillOnWholeCores() {
		// a need rounded to four places, as a summary shows it, can come out above
		// the busy cores over the target: whole cores are a fact of the size, not
		// of the figures
		Map<String, Double> need = Map.of(Need.BUSY_CORES.json(), 0.1234, Need.CPU_CORES.json(), 0.2057);

		assertThat(NeedText.of(need, EIGHT_VCPU, "each replica", null)).containsEntry(Spec.CPU_CORES.json(),
				"needs 0.21 vCPU (0.12 busy at the peak, at a 60% target) · each replica has 8 vCPU");
	}

}
