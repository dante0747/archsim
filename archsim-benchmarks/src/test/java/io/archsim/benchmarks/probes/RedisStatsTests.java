package io.archsim.benchmarks.probes;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis's own command statistics, as {@code INFO commandstats} prints them.
 */
class RedisStatsTests {

	@Test
	void of_commandStatistics_isEachCommandsCallsAndMicroseconds() {
		// redis-cli prints Redis's CRLF line ends
		String info = """
				# Commandstats\r
				cmdstat_get:calls=1200,usec=960,usec_per_call=0.80,rejected_calls=0,failed_calls=0\r
				cmdstat_setex:calls=300,usec=450,usec_per_call=1.50,rejected_calls=0,failed_calls=0\r
				""";

		assertThat(RedisStats.of(info)).isEqualTo(JsonText
			.read("{\"get\": {\"calls\": 1200.0, \"usec\": 960.0}, \"setex\": {\"calls\": 300.0, \"usec\": 450.0}}"));
	}

}
