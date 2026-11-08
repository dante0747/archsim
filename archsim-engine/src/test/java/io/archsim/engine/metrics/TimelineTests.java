package io.archsim.engine.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's timeline: the measurement window's whole buckets, as the Python engine's report
 * takes them.
 */
class TimelineTests {

	@Test
	void windowBuckets_aWindowOfWholeBuckets_runFromItsFirstToItsLast() {
		Timeline timeline = new Timeline(8000, 1000, 6000);

		assertThat(timeline.bucketMs()).isEqualTo(100);
		assertThat(timeline.firstWindowBucket()).isEqualTo(10);
		assertThat(timeline.windowBucketsEnd()).isEqualTo(60);
	}

	@Test
	void windowBucketsEnd_aWindowShorterThanABucket_isPastTheFirst() {
		Timeline timeline = new Timeline(8000, 5950, 6000);

		assertThat(timeline.firstWindowBucket()).isEqualTo(60);
		assertThat(timeline.windowBucketsEnd()).isEqualTo(61);
	}

}
