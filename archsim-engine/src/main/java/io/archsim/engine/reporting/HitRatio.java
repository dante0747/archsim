package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;

/**
 * A part's hit ratio in a report, for a part that counts hits or misses (a cache): the
 * share of its lookups that hit, over the measurement window and in each bucket. A lookup
 * it refused counts among those it did not serve, beside its misses (ADR-0082).
 */
final class HitRatio {

	/** The part's hits in each bucket of the whole timeline. */
	private final long[] hits;

	/** The lookups it did not serve in each bucket: its misses and those it refused. */
	private final long[] unserved;

	private final long windowHits;

	private final long windowUnserved;

	private HitRatio(@Nullable Counter hits, List<Counter> unserved, Timeline timeline) {
		this.hits = (hits != null) ? hits.buckets() : new long[timeline.buckets()];
		this.unserved = new long[timeline.buckets()];
		unserved.forEach((counter) -> {
			long[] buckets = counter.buckets();
			for (int bucket = 0; bucket < buckets.length; bucket++) {
				this.unserved[bucket] += buckets[bucket];
			}
		});
		this.windowHits = (hits != null) ? hits.windowCount() : 0;
		this.windowUnserved = unserved.stream().mapToLong(Counter::windowCount).sum();
	}

	/**
	 * A part's lookups.
	 * @param metrics the part's metrics
	 * @return its hits, misses and refused lookups; empty for a part that counts none
	 */
	static Optional<HitRatio> of(NodeMetrics metrics) {
		@Nullable Counter hits = metrics.counters().get(NodeMetrics.HITS);
		List<Counter> unserved = Stream.of(NodeMetrics.MISSES, NodeMetrics.REFUSED_LOOKUPS)
			.map(metrics.counters()::get)
			.filter(Objects::nonNull)
			.toList();
		if (hits == null && unserved.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new HitRatio(hits, unserved, metrics.timeline()));
	}

	/**
	 * The share of the window's lookups that hit.
	 * @return the ratio, rounded; a JSON {@code null} without a lookup in the window
	 */
	JsonNode summary() {
		return Figures.ratio(this.windowHits, this.windowHits + this.windowUnserved);
	}

	/**
	 * The share of each bucket's lookups that hit.
	 * @param used how many buckets the run reached
	 * @return the series, {@code null} for a bucket without a lookup
	 */
	ArrayNode series(int used) {
		List<@Nullable Double> ratios = new ArrayList<>(this.hits.length);
		for (int bucket = 0; bucket < this.hits.length; bucket++) {
			long lookups = this.hits[bucket] + this.unserved[bucket];
			ratios.add((lookups > 0) ? (double) this.hits[bucket] / lookups : null);
		}
		return Figures.roundedSeries(ratios, used);
	}

}
