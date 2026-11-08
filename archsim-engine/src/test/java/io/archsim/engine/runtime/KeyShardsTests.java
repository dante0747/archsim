package io.archsim.engine.runtime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The shard a key goes to, as the Python engine 0.16.0's {@code shard_of} gives it. */
class KeyShardsTests {

	@ParameterizedTest
	@CsvSource(textBlock = """
			0,      3, 1
			1,      3, 1
			2,      3, 2
			41,     3, 2
			999999, 3, 1
			0,      7, 5
			5,      7, 4
			123,    7, 1
			""")
	void of_aKey_isPythonsShard(int key, int shards, int shard) {
		assertThat(KeyShards.of(key, shards)).isEqualTo(shard);
	}

}
