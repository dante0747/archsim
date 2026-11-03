package io.archsim.app.shares;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share links' ids, written as the Python API writes them.
 */
class SlugsTests {

	@Test
	void of_largest128BitNumber_isWhatPythonWrites() {
		assertThat(Slugs.of(BigInteger.TWO.pow(128).subtract(BigInteger.ONE))).isEqualTo("7chF7tm8n9klfT5MGD24n7");
	}

	@Test
	void of_smallNumber_hasItsLowestDigitFirst() {
		assertThat(Slugs.of(BigInteger.valueOf(12_345))).isEqualTo("7D30000000000000000000");
	}

	@Test
	void next_always_looksLikeASlug() {
		assertThat(Slugs.looksLikeOne(Slugs.next())).isTrue();
	}

	@Test
	void looksLikeOne_shorterText_isFalse() {
		assertThat(Slugs.looksLikeOne("abc")).isFalse();
	}

}
