package io.archsim.engine.components.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.Persistence;

/**
 * What a cache does beside its commands, as routines of their own: sampling the share of
 * its key capacity in use (the highest in each bucket), and, with RDB persistence, each
 * shard's snapshots, whose fork stalls the shard's commands for a time that grows with
 * its data.
 */
final class CacheUpkeep {

	/** The counter of snapshot forks. */
	private static final String FORKS = "forks";

	private static final double MS_PER_SECOND = 1000.0;

	private static final double MIN_SAMPLE_MS = 50.0;

	private static final double SAMPLES_PER_BUCKET = 4.0;

	private final Simulation simulation;

	private final Timeline timeline;

	private final CacheConfig config;

	private final CacheShards shards;

	private final NodeMetrics metrics;

	/** The highest share of the key capacity in use in each bucket, when sampled. */
	private final List<@Nullable Double> memoryUsed;

	/** The commands' stall each bucket's forks began, in milliseconds. */
	private final double[] forkPauseMs;

	/**
	 * A cache's upkeep at the start of a run, its routines started: the memory's sampling
	 * when the keys' capacity is bounded, and each shard's snapshots with RDB
	 * persistence.
	 * @param context the run
	 * @param config the cache's settings
	 * @param shards its shards
	 * @param metrics its metrics, which count the forks
	 */
	CacheUpkeep(SimContext context, CacheConfig config, CacheShards shards, NodeMetrics metrics) {
		this.simulation = context.simulation();
		this.timeline = context.timeline();
		this.config = config;
		this.shards = shards;
		this.metrics = metrics;
		this.memoryUsed = unsampled(this.timeline.buckets());
		this.forkPauseMs = new double[this.timeline.buckets()];
		// started here, as the Python engine starts them while it builds the part, so
		// they take their place among the events at time 0
		if (shards.capacity().isPresent()) {
			double stepMs = Math.max(MIN_SAMPLE_MS, this.timeline.bucketMs() / SAMPLES_PER_BUCKET);
			this.simulation.start((finish) -> sampleMemory(stepMs));
		}
		if (snapshots()) {
			for (CacheShard shard : shards.all()) {
				this.simulation.start((finish) -> snapshotLater(shard));
			}
		}
	}

	/**
	 * Whether the cache snapshots its data (RDB persistence).
	 * @return {@code true} when its forks stall its commands
	 */
	boolean snapshots() {
		return this.config.persistenceInEffect() == Persistence.RDB;
	}

	/**
	 * The highest share of the key capacity in use in each bucket.
	 * @return the shares, {@code null} for a bucket not sampled
	 */
	List<@Nullable Double> memoryUsed() {
		return Collections.unmodifiableList(this.memoryUsed);
	}

	/**
	 * The commands' stall the forks began in each bucket.
	 * @return milliseconds per bucket
	 */
	double[] forkPauseMs() {
		return this.forkPauseMs.clone();
	}

	// a series with no bucket sampled yet
	private static List<@Nullable Double> unsampled(int buckets) {
		List<@Nullable Double> series = new ArrayList<>(buckets);
		for (int bucket = 0; bucket < buckets; bucket++) {
			series.add(null);
		}
		return series;
	}

	private void sampleMemory(double stepMs) {
		int bucket = this.timeline.index(this.simulation.now());
		double used = (double) this.shards.keysCached() / this.shards.capacity().orElseThrow();
		Double previous = this.memoryUsed.get(bucket);
		this.memoryUsed.set(bucket, (previous != null) ? Math.max(previous, used) : used);
		this.simulation.after(stepMs, () -> sampleMemory(stepMs));
	}

	private void snapshotLater(CacheShard shard) {
		this.simulation.after(this.config.snapshotIntervalS() * MS_PER_SECOND, () -> {
			snapshot(shard);
			snapshotLater(shard);
		});
	}

	// the fork stalls the shard's commands for a time per GiB of its data
	private void snapshot(CacheShard shard) {
		double dataGib;
		if (this.config.keysExpire()) {
			dataGib = shard.keys().size() * this.config.itemSizeKb() / CacheConfig.KIB_PER_GIB;
		}
		else {
			Double memoryGib = this.config.memoryGib();
			dataGib = CacheConfig.KEY_MEMORY_SHARE * ((memoryGib != null) ? memoryGib : 0.0);
		}
		double stallMs = this.config.forkMsPerGib() * dataGib;
		if (stallMs > 0) {
			double now = this.simulation.now();
			shard.pauseUntil(now + stallMs);
			this.metrics.count(FORKS, now);
			this.forkPauseMs[this.timeline.index(now)] += stallMs;
		}
	}

}
