package io.archsim.engine.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gauge's mean in each bucket, over the part of the bucket the run simulated, and its
 * peak.
 */
class GaugeTests {

	/** Three buckets of 100 ms; the last, from 200 ms, cut short at 250 ms. */
	private final Timeline timeline = new Timeline(250, 0, 250);

	@Test
	void meanSeriesUntilEnd_aLastBucketCutShort_isTheMeanOverItsSimulatedPart() {
		Gauge gauge = new Gauge(this.timeline);
		gauge.add(0, 2);
		gauge.finish(250);

		assertThat(gauge.meanSeriesUntilEnd()).containsExactly(2.0, 2.0, 2.0);
	}

	@Test
	void meanSeriesUntilEnd_aBucketTheRunNeverReached_isNone() {
		Gauge gauge = new Gauge(this.timeline);
		gauge.add(0, 2);
		gauge.finish(150);

		assertThat(gauge.meanSeriesUntilEnd()).containsExactly(2.0, 2.0, null);
	}

	@Test
	void runPeak_aLevelThatRoseAndFell_isItsHighest() {
		Gauge gauge = new Gauge(this.timeline);
		gauge.add(0, 3);
		gauge.add(120, -2);
		gauge.finish(250);

		assertThat(gauge.runPeak()).isEqualTo(3);
	}

}
