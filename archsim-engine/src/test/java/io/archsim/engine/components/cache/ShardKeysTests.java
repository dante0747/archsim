package io.archsim.engine.components.cache;

import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.component.cache.EvictionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys a cache shard holds with the TTL model, as the Python engine 0.16.0 keeps
 * them: in order of use when their memory is bounded, evicting the least recently used,
 * or refusing new keys when full.
 */
class ShardKeysTests {

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(1000, 0, 1000), OptionalInt.empty());

	@Test
	void read_aKeyFilledAndNotExpired_hits() {
		ShardKeys keys = unbounded();
		keys.fill(1, 0, 100);

		assertThat(keys.read(1, 50)).isTrue();
	}

	@Test
	void read_aKeyAtItsExpiry_misses() {
		ShardKeys keys = unbounded();
		keys.fill(1, 0, 100);

		assertThat(keys.read(1, 100)).isFalse();
	}

	@Test
	void read_aKeyNeverFilled_misses() {
		ShardKeys keys = unbounded();

		assertThat(keys.read(2, 50)).isFalse();
	}

	@Test
	void fill_withoutAMemorySize_holdsEveryKey() {
		ShardKeys keys = unbounded();
		for (int key = 0; key < 1000; key++) {
			keys.fill(key, 0, 100);
		}

		assertThat(keys.size()).isEqualTo(1000);
		assertThat(this.metrics.counters()).isEmpty();
	}

	@Test
	void fill_aFullShardThatEvicts_evictsTheLeastRecentlyUsedKey() {
		ShardKeys keys = bounded(2, EvictionPolicy.ALLKEYS_LRU);
		keys.fill(1, 0, 100);
		keys.fill(2, 0, 100);
		keys.read(1, 10);

		keys.fill(3, 20, 120);

		assertThat(keys.read(2, 30)).isFalse();
		assertThat(keys.read(1, 30)).isTrue();
		assertThat(this.metrics.counters()).hasEntrySatisfying(ShardKeys.EVICTIONS,
				(evictions) -> assertThat(evictions.total()).isOne());
	}

	@Test
	void fill_aKeyAlreadyHeld_refreshesItWithoutEvicting() {
		ShardKeys keys = bounded(2, EvictionPolicy.ALLKEYS_LRU);
		keys.fill(1, 0, 100);
		keys.fill(2, 0, 100);

		keys.fill(1, 90, 190);

		assertThat(keys.read(1, 150)).isTrue();
		assertThat(this.metrics.counters()).isEmpty();
	}

	@Test
	void fill_aFullShardThatRefusesNewKeys_dropsExpiredKeysFirst() {
		ShardKeys keys = bounded(2, EvictionPolicy.NOEVICTION);
		keys.fill(1, 0, 10);
		keys.fill(2, 0, 100);

		boolean filled = keys.fill(3, 50, 150);

		assertThat(filled).isTrue();
		assertThat(keys.size()).isEqualTo(2);
	}

	@Test
	void fill_aFullShardThatRefusesNewKeys_refusesWhenNoKeyExpired() {
		ShardKeys keys = bounded(2, EvictionPolicy.NOEVICTION);
		keys.fill(1, 0, 100);
		keys.fill(2, 0, 100);

		boolean filled = keys.fill(3, 50, 150);

		assertThat(filled).isFalse();
		assertThat(this.metrics.counters()).hasEntrySatisfying(ShardKeys.OOM_ERRORS,
				(refusals) -> assertThat(refusals.total()).isOne());
	}

	@Test
	void fill_aFullShardThatRefusesNewKeys_looksForExpiredKeysAmongThe64LeastRecentlyUsed() {
		ShardKeys keys = bounded(70, EvictionPolicy.NOEVICTION);
		for (int key = 0; key < 64; key++) {
			keys.fill(key, 0, 1000);
		}
		for (int key = 64; key < 70; key++) {
			keys.fill(key, 0, 10);
		}

		boolean filled = keys.fill(70, 50, 150);

		assertThat(filled).isFalse();
	}

	@Test
	void invalidate_aHeldKey_dropsIt() {
		ShardKeys keys = unbounded();
		keys.fill(1, 0, 100);

		keys.invalidate(1);

		assertThat(keys.size()).isZero();
	}

	private ShardKeys unbounded() {
		return new ShardKeys(OptionalLong.empty(), EvictionPolicy.ALLKEYS_LRU, this.metrics);
	}

	private ShardKeys bounded(long capacity, EvictionPolicy policy) {
		return new ShardKeys(OptionalLong.of(capacity), policy, this.metrics);
	}

}
