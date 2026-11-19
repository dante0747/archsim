package io.archsim.benchmarks.baselines;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The share of a cache's lookups that missed in the top calibration pass.
 */
class CacheMissesTests {

	@Test
	void of_aTopPassThatCountedItsLookups_isItsMissesOverThem() {
		JsonNode calibration = JsonText.read("""
				{"passes": {"high": {"service": {"counters": {"cache_miss:measure": 25, "cache_hit:measure": 75}}}}}
				""");

		CacheMisses misses = CacheMisses.of(calibration);

		assertThat(misses.share()).isEqualTo(0.25);
		assertThat(misses.how()).isEqualTo("the top calibration pass's misses over its lookups: 25 of 100");
	}

	// the most a database behind the cache can be sent
	@Test
	void of_aTopPassThatCountedNoLookups_takesEveryLookupAsAMiss() {
		JsonNode calibration = JsonText.read("{\"passes\": {\"high\": {\"service\": {\"counters\": {}}}}}");

		CacheMisses misses = CacheMisses.of(calibration);

		assertThat(misses.share()).isEqualTo(1.0);
		assertThat(misses.how()).isEqualTo("the top calibration pass counted no lookups: every lookup taken as a miss");
	}

}
