package io.archsim.app.signin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.SigningKeys;
import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state cookie that carries a sign-in from the provider's page back to the callback:
 * signed, so nobody can forge or change it, and good for ten minutes.
 */
class StateCookiesTests {

	/**
	 * What the Python API signs for
	 * {@code {"p":"github","s":"st","v":"ver","n":"/projects/café"}}.
	 */
	private static final String PYTHONS = "eyJwIjoiZ2l0aHViIiwicyI6InN0IiwidiI6InZlciIsIm4iOiIvcHJvamVjdHMv"
			+ "Y2FmXHUwMGU5IiwiZXhwIjo0MTAyNDQ0ODAwLjI1fQ.0gyF-8kRlcthgTRdp2eIuF6lXuFybnezGUUGjZhGDaU";

	private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

	private final StateCookies cookies = cookies(NOW);

	@Test
	void verify_whatItSigned_isTheState() {
		SignInState state = this.cookies.start("github", "/projects");

		assertThat(this.cookies.verify(this.cookies.sign(state))).contains(state);
	}

	@Test
	void verify_whatThePythonApiSigned_isItsState() {
		assertThat(this.cookies.verify(PYTHONS)).map(SignInState::next).contains("/projects/café");
	}

	@Test
	void verify_changedBody_isNothing() {
		String[] parts = this.cookies.sign(this.cookies.start("github", null)).split("\\.", -1);

		assertThat(this.cookies.verify(parts[0] + "x." + parts[1])).isEmpty();
	}

	@Test
	void verify_afterTenMinutes_isNothing() {
		String signed = this.cookies.sign(this.cookies.start("github", null));

		assertThat(cookies(NOW.plusSeconds(601)).verify(signed)).isEmpty();
	}

	@Test
	void verify_noCookie_isNothing() {
		assertThat(this.cookies.verify(null)).isEmpty();
	}

	@Test
	void verify_notBase64_isNothing() {
		assertThat(this.cookies.verify("!!!.???")).isEmpty();
	}

	@Test
	void start_verifier_isLongEnoughForPkce() {
		assertThat(this.cookies.start("github", null).verifier()).hasSize(83);
	}

	private static StateCookies cookies(Instant now) {
		SigningKeys keys = new SigningKeys(
				TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop"));
		return new StateCookies(keys, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));
	}

}
