package io.archsim.engine.calibration.resources;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The working set fitted as the Python engine's {@code fit_memory} fits it, where the
 * golden files do not reach. Expected values are the Python engine's.
 */
class MemoryFitsTests {

	@Test
	void fit_rateThatHardlyChanged_isNoted() {
		MemoryFit fit = MemoryFits.fit(List.of(100.0, 101.0, 100.5), List.of(10.0, 10.5, 11.0), null).orElseThrow();

		assertThat(fit.baselineMb()).isEqualTo(95.25);
		assertThat(fit.perRpsMb()).isEqualTo(0.5);
		assertThat(fit.perRequestMb()).isEqualTo(MemoryFits.MIN_PER_REQUEST_MB);
		assertThat(fit.error()).isEqualTo(0.005);
		assertThat(fit.notes()).containsExactly("the request rate hardly changed over the range (10 to 11 per "
				+ "replica), so memory that follows it cannot be told apart from memory at idle");
	}

	@Test
	void fit_memoryThatFellWithTheRate_isNotedAsNotGrowing() {
		MemoryFit fit = MemoryFits.fit(List.of(100.0, 99.0, 98.0, 97.0), List.of(10.0, 20.0, 30.0, 40.0), null)
			.orElseThrow();

		assertThat(fit.baselineMb()).isEqualTo(98.5);
		assertThat(fit.perRpsMb()).isZero();
		assertThat(fit.error()).isEqualTo(0.0155);
		assertThat(fit.notes()).containsExactly("memory did not grow with the request rate over the range");
	}

	@Test
	void fit_requestsHeldAlone_isFittedAgainstThem() {
		MemoryFit fit = MemoryFits.fit(List.of(100.0, 110.0, 120.0), null, List.of(1.0, 6.0, 11.0)).orElseThrow();

		assertThat(fit.baselineMb()).isEqualTo(98.0);
		assertThat(fit.perRequestMb()).isEqualTo(2.0);
		assertThat(fit.against()).containsExactly("held");
		assertThat(fit.notes()).isEmpty();
	}

	@Test
	void fit_noUsablePoint_isEmpty() {
		assertThat(MemoryFits.fit(List.of(Double.NaN), List.of(1.0), null)).isEmpty();
	}

	@Test
	void fit_againstNeither_isRefusedInThePythonEnginesWords() {
		assertThatIllegalArgumentException().isThrownBy(() -> MemoryFits.fit(List.of(1.0), null, null))
			.withMessage("fit the working set against the request rate, requests held, or both");
	}

}
