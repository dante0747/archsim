package io.archsim.benchmarks.resimulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The folder development checks are written to: the engine's own, or a label that starts
 * with its version, never another engine's.
 */
class DevLabelsTests {

	@Test
	void of_noLabel_isTheEnginesVersion() {
		assertThat(DevLabels.of(null)).isEqualTo("1.0.0");
	}

	@Test
	void of_aLabelThatStartsWithTheEnginesVersion_isThatLabel() {
		assertThat(DevLabels.of("1.0.0-port")).isEqualTo("1.0.0-port");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.16.0-port", "0.16.0-java", "1.0.0x" })
	void of_aLabelThatNamesAnotherEngine_isRefused(String label) {
		assertThatIllegalArgumentException().isThrownBy(() -> DevLabels.of(label))
			.withMessage("a development check's label starts with 1.0.0- and names this engine: not " + label);
	}

}
