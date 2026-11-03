package io.archsim.app.signin;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in cookies, written as the Python API writes them.
 */
class BrowserCookieTests {

	@Test
	void set_session_isHttpOnlySecureAndLax() {
		assertThat(BrowserCookie.SESSION.set("abc-_123", 2_592_000))
			.isEqualTo("__Host-archsim_session=abc-_123; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax; Secure");
	}

	@Test
	void set_csrf_isReadableByScriptsAndStrict() {
		assertThat(BrowserCookie.CSRF.set("tok", 60))
			.isEqualTo("__Host-archsim_csrf=tok; Max-Age=60; Path=/; SameSite=strict; Secure");
	}

	@Test
	void cleared_state_isEmptyAndExpiresNow() {
		assertThat(BrowserCookie.STATE.cleared(Instant.parse("2026-11-03T06:41:15Z")))
			.isEqualTo("__Host-archsim_oauth=\"\"; expires=Tue, 03 Nov 2026 06:41:15 GMT; HttpOnly; Max-Age=0; "
					+ "Path=/; SameSite=lax; Secure");
	}

}
