package io.archsim.engine.reporting;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;

import static org.assertj.core.api.Assertions.assertThat;

/** A part's hit ratio in a report, as the Python engine 0.16.0 writes it. */
class HitRatioTests {

	private final Timeline timeline = new Timeline(1000, 0, 1000);

	private final NodeMetrics metrics = new NodeMetrics(this.timeline, OptionalInt.empty());

	@Test
	void of_aPartWithoutHitsOrMisses_isEmpty() {
		assertThat(HitRatio.of(this.metrics)).isEmpty();
	}

	@Test
	void summary_hitsAndMisses_isTheShareThatHit() {
		this.metrics.count(NodeMetrics.HITS, 10);
		this.metrics.count(NodeMetrics.HITS, 20);
		this.metrics.count(NodeMetrics.HITS, 30);
		this.metrics.count(NodeMetrics.MISSES, 40);

		double ratio = HitRatio.of(this.metrics).orElseThrow().summary().asDouble();

		assertThat(ratio).isEqualTo(0.75);
	}

	@Test
	void summary_onlyMisses_isZero() {
		this.metrics.count(NodeMetrics.MISSES, 10);

		double ratio = HitRatio.of(this.metrics).orElseThrow().summary().asDouble();

		assertThat(ratio).isZero();
	}

	@Test
	void series_aBucketWithHitsAndMisses_isItsShareThatHit() {
		this.metrics.count(NodeMetrics.HITS, 10);
		this.metrics.count(NodeMetrics.MISSES, 10);
		this.metrics.count(NodeMetrics.MISSES, 10);

		ArrayNode series = HitRatio.of(this.metrics).orElseThrow().series(this.timeline.buckets());

		assertThat(series.get(0).asDouble()).isEqualTo(0.3333);
	}

	@Test
	void series_aBucketWithoutLookups_isNull() {
		this.metrics.count(NodeMetrics.MISSES, 10);
		int buckets = this.timeline.buckets();

		ArrayNode series = HitRatio.of(this.metrics).orElseThrow().series(buckets);

		assertThat(series.get(buckets - 1).isNull()).isTrue();
	}

}
