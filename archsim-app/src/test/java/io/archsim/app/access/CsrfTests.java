package io.archsim.app.access;

import org.junit.jupiter.api.Test;

import io.archsim.app.config.TestProperties;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The CSRF token of a session: derived from its id and the secret key, so every instance,
 * Python's included, agrees on it without storing it.
 */
class CsrfTests {

	private static final String SESSION = "0".repeat(64);

	/** What the Python API derives for {@link #SESSION} with the same key. */
	private static final String PYTHONS_TOKEN = "G6KBsz_4U1ZUEQXK5vTOvMksQY485UA-1OmxziJyw4M";

	private final Csrf csrf = new Csrf(
			new SigningKeys(TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop")));

	@Test
	void token_session_isThePythonApisToken() {
		assertThat(this.csrf.token(SESSION)).isEqualTo(PYTHONS_TOKEN);
	}

	@Test
	void check_sessionsToken_passes() {
		this.csrf.check(PYTHONS_TOKEN, SESSION);
	}

	@Test
	void check_noHeader_isForbiddenAndSaysWhatToSend() {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.csrf.check(null, SESSION))
			.satisfies((ex) -> assertThat(ex.status().value()).isEqualTo(403))
			.withMessage("Missing or wrong X-ArchSim-CSRF header: send the csrfToken from GET /auth/me "
					+ "(or the __Host-archsim_csrf cookie) with every change");
	}

	@Test
	void check_anotherSessionsToken_isForbidden() {
		String other = this.csrf.token("1".repeat(64));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.csrf.check(other, SESSION));
	}

	@Test
	void check_nonAsciiHeader_isForbidden() {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.csrf.check("café", SESSION));
	}

}
