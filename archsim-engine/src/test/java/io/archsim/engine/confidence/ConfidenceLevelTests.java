package io.archsim.engine.confidence;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A confidence level lowered to a cap. */
class ConfidenceLevelTests {

	@ParameterizedTest(name = "{0} capped at {1} is {1}")
	@CsvSource({ "HIGH, MEDIUM", "HIGH, LOW", "MEDIUM, LOW" })
	void cappedAt_aLowerCap_isTheCap(ConfidenceLevel level, ConfidenceLevel cap) {
		assertThat(level.cappedAt(cap)).isEqualTo(cap);
	}

	@ParameterizedTest(name = "{0} capped at {1} is {0}")
	@CsvSource({ "LOW, MEDIUM", "MEDIUM, MEDIUM", "MEDIUM, HIGH" })
	void cappedAt_aCapAtOrAboveTheLevel_keepsTheLevel(ConfidenceLevel level, ConfidenceLevel cap) {
		assertThat(level.cappedAt(cap)).isEqualTo(level);
	}

}
