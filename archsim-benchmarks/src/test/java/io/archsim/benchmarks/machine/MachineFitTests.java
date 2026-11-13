package io.archsim.benchmarks.machine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A machine's fit as a what-if file gives it, and its digest, which names the work done
 * on it.
 */
class MachineFitTests {

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	@Test
	void of_theFitsKeys_isTheFit() {
		assertThat(MachineFit.of(FIT.json())).isEqualTo(FIT);
	}

	@Test
	void of_aFitWithoutAKey_namesIt() {
		ObjectNode fit = FIT.json();
		fit.remove("allCoreClock");

		assertThatIllegalArgumentException().isThrownBy(() -> MachineFit.of(fit)).withMessageContaining("allCoreClock");
	}

	@Test
	void digest_theSameFit_isTheSame() {
		assertThat(MachineFit.of(FIT.json()).digest()).isEqualTo(FIT.digest()).hasSize(16);
	}

	@Test
	void digest_anotherClock_isAnother() {
		assertThat(FIT.withClock("0.9").digest()).isNotEqualTo(FIT.digest());
	}

}
