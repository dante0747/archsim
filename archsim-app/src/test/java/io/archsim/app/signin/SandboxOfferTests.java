package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a server offers sandboxes (only with sign-in and {@code ARCHSIM_SANDBOXES}),
 * the requests that start one ({@code POST /auth/sandbox}, which then takes the browser's
 * CSRF token as a session's changes do), and the token a browser starts one with.
 */
class SandboxOfferTests {

	private static final String SECRET_KEY = "secret-key=test-secret-key-0123456789-abcdefghijklmnop";

	private static final DefaultCsrfToken TOKEN = new DefaultCsrfToken("X-ArchSim-CSRF", "_csrf", "the-token");

	private final SandboxOffer offered = offer("auth-mode=oauth", "sandboxes=true");

	@Test
	void offered_hostedWithTheSetting_isTrue() {
		assertThat(this.offered.offered()).isTrue();
	}

	@Test
	void offered_hostedWithoutTheSetting_isFalse() {
		assertThat(offer("auth-mode=oauth", "sandboxes=false").offered()).isFalse();
	}

	@Test
	void offered_withoutSignIn_isFalseWhateverTheSetting() {
		assertThat(offer("auth-mode=none", "sandboxes=true").offered()).isFalse();
	}

	@Test
	void starts_postToTheSandboxPath_isAStart() {
		assertThat(this.offered.starts()).hasValueSatisfying(
				(starts) -> assertThat(starts.matches(new MockHttpServletRequest("POST", "/api/v1/auth/sandbox")))
					.isTrue());
	}

	@Test
	void starts_getOfTheSandboxPath_isNoStart() {
		assertThat(this.offered.starts()).hasValueSatisfying(
				(starts) -> assertThat(starts.matches(new MockHttpServletRequest("GET", "/api/v1/auth/sandbox")))
					.isFalse());
	}

	@Test
	void starts_sandboxesOff_areNone() {
		assertThat(offer("auth-mode=oauth", "sandboxes=false").starts()).isEmpty();
	}

	@Test
	void csrfTokenFor_sandboxesOn_isTheBrowsersToken() {
		assertThat(this.offered.csrfTokenFor(TOKEN)).isEqualTo("the-token");
	}

	@Test
	void csrfTokenFor_sandboxesOff_isNone() {
		assertThat(offer("auth-mode=oauth", "sandboxes=false").csrfTokenFor(TOKEN)).isNull();
	}

	private static SandboxOffer offer(String authMode, String sandboxes) {
		return new SandboxOffer(TestProperties.with(authMode, "public-url=https://archsim.test"),
				TestProperties.signIn(SECRET_KEY, sandboxes));
	}

}
