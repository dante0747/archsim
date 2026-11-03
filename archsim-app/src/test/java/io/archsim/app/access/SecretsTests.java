package io.archsim.app.access;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secrets made and kept as the Python API makes and keeps them.
 */
class SecretsTests {

	@Test
	void sha256Hex_text_isItsDigestInLowerCaseHex() {
		assertThat(Secrets.sha256Hex("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void newSecret_withPrefix_isThePrefixAndFortyThreeUrlSafeCharacters() {
		String secret = Secrets.newSecret(Secrets.TOKEN_PREFIX);

		assertThat(secret).matches("asim_pat_[A-Za-z0-9_-]{43}");
	}

	@Test
	void newSecret_twice_differs() {
		assertThat(Secrets.newSecret("")).isNotEqualTo(Secrets.newSecret(""));
	}

}
