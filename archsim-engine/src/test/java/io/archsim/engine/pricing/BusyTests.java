package io.archsim.engine.pricing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.model.pricing.Sku;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a burstable type can run a part on its CPU credits. */
class BusyTests {

	private static final Sku BURSTABLE = new Sku("t4g.small", "t4g", 2, 2, Map.of("on_demand", 0.0168), List.of(), true,
			0.2, 0.04);

	@Test
	void runsOnCredits_underTheBaselineAndThePeakTarget_isTrue() {
		assertThat(new Busy(1.2, 0.32).runsOnCredits(BURSTABLE)).isTrue();
	}

	@Test
	void runsOnCredits_overTheBaselineAtTheBaseLoad_isFalse() {
		assertThat(new Busy(0.5, 0.33).runsOnCredits(BURSTABLE)).isFalse();
	}

	@Test
	void runsOnCredits_overThePeakTarget_isFalse() {
		assertThat(new Busy(1.21, 0.1).runsOnCredits(BURSTABLE)).isFalse();
	}

	@Test
	void runsOnCredits_aTypeThatIsNotBurstable_isFalse() {
		Sku fixed = new Sku("c6g.large", "c6g", 2, 4, Map.of("on_demand", 0.068), List.of(), false, null, null);

		assertThat(new Busy(0.1, 0.1).runsOnCredits(fixed)).isFalse();
	}

}
