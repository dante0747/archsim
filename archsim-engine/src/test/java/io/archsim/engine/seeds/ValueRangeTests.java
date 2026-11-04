package io.archsim.engine.seeds;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A figure's range across seeds, and how it reads. */
class ValueRangeTests {

	@Test
	void of_valuesWithGaps_spansTheValuesThatAreSet() {
		List<@Nullable Double> values = Arrays.asList(3.0, null, 1.25, 7.0);

		assertThat(ValueRange.of(values)).hasValue(new ValueRange(1.25, 7.0, 3.0));
	}

	@Test
	void of_noValueSet_isNoRange() {
		List<@Nullable Double> values = Arrays.asList((Double) null);

		assertThat(ValueRange.of(values)).isEmpty();
	}

	@Test
	void text_aSpread_namesBothEndsAndTheMedian() {
		ValueRange range = new ValueRange(1.25, 7.0, 3.0);

		assertThat(range.text("p95")).isEqualTo("p95 1.3–7.0 ms (median 3.0)");
	}

	@Test
	void text_endsThatReadTheSame_nameOneValue() {
		ValueRange range = new ValueRange(12.5, 12.5, 12.5);

		assertThat(range.text("p95")).isEqualTo("p95 13 ms");
	}

}
