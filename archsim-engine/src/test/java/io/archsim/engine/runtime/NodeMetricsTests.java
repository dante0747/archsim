package io.archsim.engine.runtime;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.archsim.engine.metrics.Timeline;

import static org.assertj.core.api.Assertions.assertThat;

/** What a part measures of its own: counts and per-bucket means. */
class NodeMetricsTests {

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(1000, 0, 1000), OptionalInt.empty());

	@Test
	void count_severalAtOnce_countsThemAll() {
		this.metrics.count("diskReads", 10, 3);

		assertThat(this.metrics.counters()).hasEntrySatisfying("diskReads",
				(reads) -> assertThat(reads.total()).isEqualTo(3));
	}

	@Test
	void recordMean_samples_averageOverTheWindow() {
		this.metrics.recordMean("lockWaitMs", 10, 2.0);
		this.metrics.recordMean("lockWaitMs", 20, 4.0);

		assertThat(this.metrics.means()).hasEntrySatisfying("lockWaitMs",
				(waits) -> assertThat(waits.windowMean()).hasValue(3.0));
	}

}
