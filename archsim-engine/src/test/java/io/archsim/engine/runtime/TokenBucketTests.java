package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The token bucket beyond the frozen engine's recorded runs.
 */
class TokenBucketTests {

	@Test
	void ofRate_noRate_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> TokenBucket.ofRate(0))
			.withMessage("a token bucket's rate must be above 0; got 0.0");
	}

	@Test
	void tryTake_aFullBucketOfOneToken_takesOneThenRefuses() {
		TokenBucket bucket = TokenBucket.ofRate(0.5);

		boolean first = bucket.tryTake(0, 1.0);
		boolean second = bucket.tryTake(0, 1.0);

		assertThat(new boolean[] { first, second }).containsExactly(true, false);
	}

}
