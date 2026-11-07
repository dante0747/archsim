package io.archsim.engine.calibration.fit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A Prometheus histogram over a range, as the Python engine's {@code Histogram} reads its
 * buckets and gives its quantiles.
 */
class HistogramTests {

	@Test
	void fromBuckets_countThatFalls_isRaisedToTheOneBefore() {
		Map<Double, Double> buckets = new LinkedHashMap<>();
		buckets.put(2.0, 4.0);
		buckets.put(1.0, 5.0);
		buckets.put(Double.POSITIVE_INFINITY, 6.0);

		assertThat(Histogram.fromBuckets(buckets, 1.0).cumulative()).containsExactly(5.0, 5.0);
	}

	@Test
	void fromBuckets_scale_turnsTheBoundsIntoMilliseconds() {
		assertThat(Histogram.fromBuckets(Map.of(0.1, 1.0, 0.2, 2.0), 1000.0).bounds()).containsExactly(100.0, 200.0);
	}

	@Test
	void fromBuckets_noInfiniteBucket_takesTheTotalFromTheLastBound() {
		assertThat(Histogram.fromBuckets(Map.of(1.0, 3.0), 1.0).total()).isEqualTo(3.0);
	}

	@Test
	void quantile_inTheInfiniteBucket_isTheHighestFiniteBound() {
		Histogram histogram = new Histogram(List.of(10.0, 100.0), List.of(3.0, 9.0), 11.0);

		assertThat(histogram.quantile(0.95)).hasValue(100.0);
	}

	@Test
	void quantile_withoutObservations_isEmpty() {
		assertThat(new Histogram(List.of(1.0), List.of(0.0), 0.0).quantile(0.5)).isEmpty();
	}

	@Test
	void beyondShare_withinFloatNoiseOfNone_isNone() {
		assertThat(new Histogram(List.of(1.0), List.of(1.0 - 1e-12), 1.0).beyondShare()).isZero();
	}

	@Test
	void bucketOf_quantileInAFiniteBucket_isItsBounds() {
		Histogram histogram = new Histogram(List.of(10.0, 100.0), List.of(3.0, 9.0), 11.0);

		assertThat(histogram.bucketOf(0.5)).hasValue(new Histogram.Bucket(10.0, 100.0));
	}

	@Test
	void bucketOf_quantileInTheInfiniteBucket_isEmpty() {
		Histogram histogram = new Histogram(List.of(10.0, 100.0), List.of(3.0, 9.0), 11.0);

		assertThat(histogram.bucketOf(0.95)).isEmpty();
	}

	@Test
	void constructor_boundsAndCountsThatDoNotPair_areRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Histogram(List.of(1.0), List.of(), 1.0))
			.withMessage("each bound needs its cumulative count");
	}

}
