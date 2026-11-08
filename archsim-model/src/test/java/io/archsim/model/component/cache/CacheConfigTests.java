package io.archsim.model.component.cache;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;

/** What a cache's settings mean. Each case is what the Python engine 0.16.0 gives. */
class CacheConfigTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void heldKeys_withMemory_isWhatEveryShardHolds() {
		assertThat(config("{\"memoryGib\": 1, \"shards\": 3}").heldKeys()).hasValue(2_516_580L);
	}

	@Test
	void heldKeys_withoutMemory_isEmpty() {
		assertThat(config("{}").heldKeys()).isEmpty();
	}

	@Test
	void evictionInEffect_onAuto_isTheTechnologysLeastRecentlyUsed() {
		assertThat(config("{\"technology\": \"memcached\"}").evictionInEffect()).isEqualTo(EvictionPolicy.ALLKEYS_LRU);
	}

	@Test
	void evictionInEffect_setExplicitly_isTheSetting() {
		assertThat(config("{\"evictionPolicy\": \"noeviction\"}").evictionInEffect())
			.isEqualTo(EvictionPolicy.NOEVICTION);
	}

	@Test
	void persistenceInEffect_onAutoForRedis_isNone() {
		assertThat(config("{}").persistenceInEffect()).isEqualTo(Persistence.NONE);
	}

	@Test
	void persistenceInEffect_onAutoForATechnologyWithoutADefault_isNone() {
		assertThat(config("{\"technology\": \"cdn\"}").persistenceInEffect()).isEqualTo(Persistence.NONE);
	}

	@Test
	void persistenceInEffect_setExplicitly_isTheSetting() {
		assertThat(config("{\"technology\": \"varnish\", \"persistence\": \"rdb\"}").persistenceInEffect())
			.isEqualTo(Persistence.RDB);
	}

	@Test
	void keysExpire_withTheTtlModel_isTrue() {
		assertThat(config("{\"hitModel\": \"ttl_keyspace\"}").keysExpire()).isTrue();
	}

	@Test
	void keysExpire_withAFixedRatio_isFalse() {
		assertThat(config("{}").keysExpire()).isFalse();
	}

	private static CacheConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), CacheConfig.class);
	}

}
