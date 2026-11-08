package io.archsim.engine.runtime;

/**
 * Which shard of a part a request's key goes to, as the Python engine hashes keys for
 * caches and databases: Knuth's multiplicative hash, so that popular key ranks do not
 * cluster on one shard.
 */
public final class KeyShards {

	private static final long HASH_MULTIPLIER = 2_654_435_761L;

	private static final long LOW_32_BITS = 0xFFFF_FFFFL;

	private KeyShards() {
	}

	/**
	 * The shard a key goes to.
	 * @param key the key's rank, from 0
	 * @param shards how many shards there are, at least one
	 * @return the shard, from 0
	 */
	public static int of(int key, int shards) {
		// the rank plus one, so that rank 0 does not hash to 0, times the multiplier, in
		// 32 bits
		long hash = ((key + 1L) * HASH_MULTIPLIER) & LOW_32_BITS;
		return (int) (hash % shards);
	}

}
