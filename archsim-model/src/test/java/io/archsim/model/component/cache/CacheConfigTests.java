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

	private static CacheConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), CacheConfig.class);
	}

}
