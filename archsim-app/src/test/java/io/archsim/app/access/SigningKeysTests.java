package io.archsim.app.access;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The keys derived from {@code ARCHSIM_SECRET_KEY}, one per purpose.
 */
class SigningKeysTests {

	private static final String KEY = "secret-key=test-secret-key-0123456789-abcdefghijklmnop";

	@Test
	void mac_stateCookieBody_isWhatThePythonApiSigns() {
		SigningKeys keys = new SigningKeys(TestProperties.signIn(KEY));

		byte[] mac = keys.mac("oauth-state", "eyJwIjoiZ2l0aHViIiwiZXhwIjoxLjV9".getBytes(StandardCharsets.US_ASCII));

		assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(mac))
			.isEqualTo("ypIb0RBfOvvyJDakPt0q89UQ2_vmr-zmhf4CVYcCKOg");
	}

	@Test
	void mac_withoutSecretKey_cannotSign() {
		SigningKeys keys = new SigningKeys(TestProperties.signIn());

		assertThatIllegalStateException().isThrownBy(() -> keys.mac("csrf", new byte[0]));
	}

}
