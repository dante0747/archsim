package io.archsim.engine.random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The Zipf sampler's behaviour beyond the recorded ranks ({@link RandomCasesTests}).
 */
class ZipfSamplerTests {

	@Test
	void constructor_noKeys_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new ZipfSampler(0, 1.0))
			.withMessage("a Zipf sampler needs at least one key; got 0");
	}

	@Test
	void sample_oneKey_isAlwaysRankZero() {
		ZipfSampler sampler = new ZipfSampler(1, 1.2);

		int rank = sampler.sample(new RandomStreams(7).stream("keys"));

		assertThat(rank).isZero();
	}

}
