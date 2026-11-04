package io.archsim.engine.queueing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The share of requests the most popular keys get under Zipf popularity: Python's values.
 */
class ZipfTests {

	@Test
	void share_noSkew_isTheKeysShareOfTheKeyspace() {
		assertThat(Zipf.share(10, 0, 1)).isEqualTo(0.1);
	}

	@Test
	void share_skewedKeys_favoursThePopularOnes() {
		assertThat(Zipf.share(10, 0.99, 1)).isCloseTo(0.3382826891156122, within(1e-15));
	}

	@Test
	void share_asManyTopKeysAsKeys_isEverything() {
		assertThat(Zipf.share(10, 1.2, 10)).isEqualTo(1.0);
	}

	@Test
	void share_aLargeKeyspace_approximatesItsTail() {
		assertThat(Zipf.share(1_000_000, 0.99, 10)).isCloseTo(0.19205667708552535, within(1e-15));
	}

	@Test
	void share_aLargeKeyspaceWithSkewOne_approximatesItsTailByTheLogarithm() {
		assertThat(Zipf.share(100_000, 1.0, 1)).isCloseTo(0.08271202041593927, within(1e-15));
	}

	@Test
	void popularity_skewOfOne_isTheLogarithm() {
		assertThat(Zipf.popularity(2, 8, 1.0)).isEqualTo(Math.log(4));
	}

	@Test
	void popularity_anotherSkew_isThePowerIntegral() {
		assertThat(Zipf.popularity(1, 4, 0.5)).isEqualTo(2.0);
	}

	@Test
	void share_noKeys_isNothing() {
		assertThat(Zipf.share(0, 1.0, 1)).isZero();
	}

}
