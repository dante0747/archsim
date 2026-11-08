package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;

/**
 * A part's hit ratio in a report, for a part that counts hits or misses (a cache): the
 * share of its lookups that hit, over the measurement window and in each bucket.
 */
final class HitRatio {

	/** The part's hits in each bucket of the whole timeline. */
	private final long[] hits;

	/** Its misses in each bucket. */
	private final long[] misses;

	private final long windowHits;

	private final long windowMisses;

	private HitRatio(@Nullable Counter hits, @Nullable Counter misses, Timeline timeline) {
		this.hits = (hits != null) ? hits.buckets() : new long[timeline.buckets()];
		this.misses = (misses != null) ? misses.buckets() : new long[timeline.buckets()];
		this.windowHits = (hits != null) ? hits.windowCount() : 0;
		this.windowMisses = (misses != null) ? misses.windowCount() : 0;
	}

	/**
	 * A part's lookups.
	 * @param metrics the part's metrics
	 * @return its hits and misses; empty for a part that counts neither
	 */
	static Optional<HitRatio> of(NodeMetrics metrics) {
		@Nullable Counter hits = metrics.counters().get(NodeMetrics.HITS);
		@Nullable Counter misses = metrics.counters().get(NodeMetrics.MISSES);
		if (hits == null && misses == null) {
			return Optional.empty();
		}
		return Optional.of(new HitRatio(hits, misses, metrics.timeline()));
	}

	/**
	 * The share of the window's lookups that hit.
	 * @return the ratio, rounded; a JSON {@code null} without a lookup in the window
	 */
	JsonNode summary() {
		return Figures.ratio(this.windowHits, this.windowHits + this.windowMisses);
	}

	/**
	 * The share of each bucket's lookups that hit.
	 * @param used how many buckets the run reached
	 * @return the series, {@code null} for a bucket without a lookup
	 */
	ArrayNode series(int used) {
		List<@Nullable Double> ratios = new ArrayList<>(this.hits.length);
		for (int bucket = 0; bucket < this.hits.length; bucket++) {
			long lookups = this.hits[bucket] + this.misses[bucket];
			ratios.add((lookups > 0) ? (double) this.hits[bucket] / lookups : null);
		}
		return Figures.roundedSeries(ratios, used);
	}

}
