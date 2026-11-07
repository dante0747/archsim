package io.archsim.engine.calibration.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.model.codec.BindingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A range written {@code START/END}, read as the Python engine's {@code parse_range}
 * reads it.
 */
class TimeRangeTests {

	@Test
	void parse_textWithSeveralSlashes_isSplitAtTheFirst() {
		assertThat(TimeRange.parse("a/b/c", 30)).isEqualTo(new TimeRange("a", "b/c", 30));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z/" })
	void parse_nothingAfterASlash_isRefusedInThePythonEnginesWords(String text) {
		assertThatIllegalArgumentException().isThrownBy(() -> TimeRange.parse(text, 60))
			.withMessage("a range is START/END, e.g. 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z");
	}

	@Test
	void parse_stepAboveADay_isRefusedAsPydanticRefusesIt() {
		assertThatExceptionOfType(BindingException.class).isThrownBy(() -> TimeRange.parse("a/b", 86_401))
			.withMessage("step: Input should be less than or equal to 86400");
	}

}
