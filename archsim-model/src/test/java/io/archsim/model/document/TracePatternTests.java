package io.archsim.model.document;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A trace's own checks hold for any number, NaN included: a record can be made without
 * the codec, and a comparison with NaN is false whichever way it asks.
 */
class TracePatternTests {

	@Test
	void new_aTimeThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> trace(new TracePoint(0, 50), new TracePoint(Double.NaN, 50)))
			.withMessage("trace times must rise strictly");
	}

	@Test
	void new_aTimeThatIsNotANumberBetweenTwoTimes_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> trace(new TracePoint(0, 50), new TracePoint(Double.NaN, 50), new TracePoint(30, 50)))
			.withMessage("trace times must rise strictly");
	}

	@Test
	void new_aRateThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> trace(new TracePoint(0, 50), new TracePoint(10, Double.NaN)))
			.withMessage("trace rates must be between 0 and 100,000 req/s");
	}

	private static TracePattern trace(TracePoint... points) {
		return new TracePattern(List.of(points), null);
	}

}
