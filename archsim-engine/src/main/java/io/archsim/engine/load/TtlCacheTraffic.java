package io.archsim.engine.load;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.queueing.Zipf;

/**
 * The traffic a TTL cache sees, for the hit ratio it may expect: a key read after it
 * expired, or after a write invalidated it, misses and is cached again, and the reads in
 * the next {@code min(TTL, time to the next write)} hit. Per key with read rate {@code r}
 * and write rate {@code w} that window lasts {@code W = (1 - e^(-w·TTL)) / w} on average,
 * so its hit ratio is {@code r·W / (1 + r·W)}. A cache whose memory holds fewer keys than
 * the keyspace keeps roughly the most popular ones (the least recently used are evicted):
 * hits are summed over the keys it holds, and reads of the others miss.
 *
 * @param readRps the reads that reach the cache, per second
 * @param writeRps the writes that invalidate its keys, per second
 * @param keys how many keys there are
 * @param skew the Zipf exponent of the keys' popularity
 * @param ttlSeconds how long a cached key lives
 * @param capacityKeys how many keys its memory holds, or {@code null} when it holds them
 * all
 */
public record TtlCacheTraffic(double readRps, double writeRps, long keys, double skew, double ttlSeconds,
		@Nullable Long capacityKeys) {

	/** Up to this many of the most popular keys are weighed one by one. */
	private static final long KEYS_WEIGHED_ALONE = 2000;

	/** Beyond them, each bucket of keys is this much wider than where it starts. */
	private static final double BUCKET_GROWTH = 1.05;

	/** A write rate times TTL below this never invalidates a key before it expires. */
	private static final double NO_WRITES = 1e-9;

	/**
	 * The expected share of reads that hit.
	 * @return the hit ratio, from 0 to 1; zero without reads, keys or a TTL
	 */
	public double hitRatio() {
		if (this.readRps <= 0 || this.keys <= 0 || this.ttlSeconds <= 0) {
			return 0.0;
		}
		List<Bucket> buckets = buckets();
		double total = FloatSum.of(buckets.stream().mapToDouble((bucket) -> bucket.weight() * bucket.count()));
		if (total <= 0) {
			return 0.0;
		}
		double held = held();
		double hits = 0.0;
		for (Bucket bucket : buckets) {
			double cached = Math.min(bucket.count(), Math.max(0.0, held - bucket.firstRank()));
			if (cached <= 0) {
				break;
			}
			double share = bucket.weight() / total;
			hits += cached * share * keyHitRatio(share);
		}
		return Math.min(1.0, Math.max(0.0, hits));
	}

	/** The keys in buckets of similar popularity, the most popular first. */
	private List<Bucket> buckets() {
		int alone = (int) Math.min(this.keys, KEYS_WEIGHED_ALONE);
		List<Bucket> buckets = new ArrayList<>();
		for (int rank = 1; rank <= alone; rank++) {
			buckets.add(new Bucket(StrictMath.pow(rank, -this.skew), 1.0, rank - 1));
		}
		long low = alone;
		while (low < this.keys) {
			long high = Math.min(this.keys, Math.max(low + 1, (long) Math.ceil(low * BUCKET_GROWTH)));
			long count = high - low;
			double popularity = Zipf.popularity(low + Zipf.HALF_RANK, high + Zipf.HALF_RANK, this.skew);
			buckets.add(new Bucket(popularity / count, count, low));
			low = high;
		}
		return buckets;
	}

	/**
	 * The hit ratio of one key: reads within the window after a miss hit.
	 * @param share the key's share of the traffic
	 * @return its hit ratio
	 */
	private double keyHitRatio(double share) {
		double reads = this.readRps * share;
		double writes = this.writeRps * share;
		double window = (writes * this.ttlSeconds < NO_WRITES) ? this.ttlSeconds
				: -StrictMath.expm1(-writes * this.ttlSeconds) / writes;
		return reads * window / (1 + reads * window);
	}

	private double held() {
		return (this.capacityKeys != null) ? Math.max(0, Math.min(this.keys, this.capacityKeys)) : this.keys;
	}

	/**
	 * Keys of similar popularity.
	 *
	 * @param weight the popularity of each key in it
	 * @param count how many keys it holds
	 * @param firstRank the rank of its first key, less one
	 */
	private record Bucket(double weight, double count, long firstRank) {
	}

}
