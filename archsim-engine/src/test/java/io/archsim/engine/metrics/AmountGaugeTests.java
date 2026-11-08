package io.archsim.engine.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An amount that need not be whole, integrated over each bucket and the window. */
class AmountGaugeTests {

	/** Three buckets of 100 ms; the last, from 200 ms, cut short at 250 ms. */
	private final Timeline timeline = new Timeline(250, 0, 250);

	@Test
	void area_anAmountHeldAcrossBuckets_isSplitAtTheirEdges() {
		AmountGauge gauge = new AmountGauge(this.timeline);
		gauge.add(50, 0.5);
		gauge.finish(250);

		assertThat(gauge.area()).containsExactly(25.0, 50.0, 25.0);
	}

	@Test
	void area_anAmountBackToZero_addsNothingMore() {
		AmountGauge gauge = new AmountGauge(this.timeline);
		gauge.add(0, 1.0);
		gauge.add(100, -1.0);
		gauge.finish(250);

		assertThat(gauge.area()).containsExactly(100.0, 0.0, 0.0);
	}

	@Test
	void finish_atTheTimeOfTheLastChange_addsNothing() {
		AmountGauge gauge = new AmountGauge(this.timeline);
		gauge.add(0, 1.0);
		gauge.add(150, 1.0);
		gauge.finish(150);

		assertThat(gauge.area()).containsExactly(100.0, 50.0, 0.0);
	}

	@Test
	void windowMean_anAmountHeldForHalfTheWindow_isHalfIt() {
		AmountGauge gauge = new AmountGauge(new Timeline(250, 0, 200));
		gauge.add(100, 0.5);
		gauge.finish(250);

		assertThat(gauge.windowMean()).isEqualTo(0.25);
	}

}
