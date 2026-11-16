package io.archsim.app.signin;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.access.BrowserCsrf;
import io.archsim.app.access.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Trying ArchSim without signing in: the web app starts a sandbox, which makes a guest
 * who owns a sandbox workspace and signs the browser in as the guest until the sandbox is
 * deleted, a day later. Starting one takes the browser's CSRF token, is limited per
 * address, and stops when the server holds as many sandboxes as it keeps.
 */
class SandboxTests extends SandboxTestSupport {

	/** How long a sandbox lasts. */
	private static final Duration LIFETIME = Duration.ofHours(24);

	/** How many sandboxes the server keeps at once. */
	private static final int MOST = 500;

	@Test
	void config_sandboxesOn_offersThemWithTheBrowsersCsrfToken() {
		MvcTestResult config = this.mvc().get().uri(CONFIG).exchange();

		assertThat(config).bodyJson().extractingPath("$.sandboxes").isEqualTo(true);
		assertThat(config).bodyJson()
			.extractingPath("$.csrfToken")
			.isEqualTo(setCookie(config, BrowserCsrf.COOKIE).orElseThrow());
	}

	@Test
	void start_fromTheWebApp_signsTheBrowserInForAsLongAsTheSandboxLasts() {
		MvcTestResult started = start(newAddress());

		assertThat(started).hasStatus(201);
		assertThat(started.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.anySatisfy((cookie) -> assertThat(cookie).startsWith(Principal.SESSION_COOKIE + "=")
				.contains("HttpOnly", "Max-Age=86400", "Secure"));
	}

	@Test
	void start_fromTheWebApp_answersAsMeAnswersTheGuest() {
		MvcTestResult started = start(newAddress());

		assertThat(started).bodyJson().isLenientlyEqualTo("""
				{"mode": "oauth", "kind": "session", "user": {"name": "Guest", "email": null, "guest": true},
				 "workspaces": [{"name": "Sandbox", "kind": "sandbox", "role": "owner", "maxConcurrentRuns": 1,
				                 "maxRunsPerHour": 20}]}""");
		assertThat(started).bodyJson()
			.extractingPath("$.csrfToken")
			.isEqualTo(setCookie(started, BrowserCsrf.COOKIE).orElseThrow());
	}

	@Test
	void me_guestsSession_saysWhenTheSandboxIsDeleted() {
		SignedIn guest = guest();

		String expiresAt = json(send(guest, HttpMethod.GET, "/api/v1/auth/me", null)).path("user")
			.path("expiresAt")
			.asString();

		assertThat(Instant.parse(expiresAt)).isCloseTo(Instant.now().plus(LIFETIME), within(1, ChronoUnit.MINUTES));
	}

	@Test
	void me_someoneSignedIn_isNoGuest() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.GET, "/api/v1/auth/me", null)).bodyJson()
			.isLenientlyEqualTo("{\"user\": {\"guest\": false, \"expiresAt\": null}}");
	}

	@Test
	void start_withoutCsrfHeader_isForbiddenAndStartsNoSession() {
		MvcTestResult refused = this.mvc().post().uri(SANDBOX).exchange();

		assertThat(refused).hasStatus(403);
		assertThat(setCookie(refused, Principal.SESSION_COOKIE)).isEmpty();
	}

	@Test
	void start_withAnotherCsrfHeaderThanItsCookie_isForbidden() {
		MvcTestResult refused = this.mvc()
			.post()
			.uri(SANDBOX)
			.cookie(new Cookie(BrowserCsrf.COOKIE, "the-browsers-own"))
			.header(BrowserCsrf.HEADER, "another-sites-guess")
			.exchange();

		assertThat(refused).hasStatus(403);
	}

	@Test
	void start_browserSignedInAlready_isConflict() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.POST, SANDBOX, null)).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("You are signed in already: sign out first to try a sandbox.");
	}

	@Test
	void start_sixthInAnHourFromOneAddress_isTooManyRequests() {
		String address = newAddress();
		startSandboxes(address, 5, 201);

		MvcTestResult sixth = start(address);

		assertThat(sixth).hasStatus(429).containsHeader(HttpHeaders.RETRY_AFTER);
	}

	@Test
	void start_refusedWhileEverySandboxIsTaken_stillCountsAgainstTheAddress() {
		String address = newAddress();
		String marker = "full" + newId().substring(0, 8);
		takePlaces(marker, MOST, Duration.ofHours(1));
		try {
			startSandboxes(address, 5, 503);

			MvcTestResult sixth = start(address);

			assertThat(sixth).hasStatus(429);
		}
		finally {
			jdbc().sql("DELETE FROM users WHERE id LIKE :marker").param("marker", marker + "%").update();
		}
	}

	@Test
	void start_everySandboxTaken_isUnavailableUntilTheFirstIsDeleted() {
		String marker = "full" + newId().substring(0, 8);
		takePlaces(marker, MOST, Duration.ofHours(1));
		try {
			MvcTestResult refused = start(newAddress());

			assertThat(refused).hasStatus(503)
				.bodyJson()
				.extractingPath("$.detail.message")
				.isEqualTo("Every sandbox is taken right now: sign in, or try again later.");
			assertThat(Long.parseLong(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(3500L,
					3600L);
		}
		finally {
			jdbc().sql("DELETE FROM users WHERE id LIKE :marker").param("marker", marker + "%").update();
		}
	}

	private void startSandboxes(String address, int count, int status) {
		for (int i = 0; i < count; i++) {
			assertThat(start(address)).hasStatus(status);
		}
	}

	/**
	 * Stores guests with no sandbox, as many as the server keeps, which expire in a
	 * while.
	 */
	private void takePlaces(String marker, int count, Duration expiresIn) {
		jdbc().sql("""
				INSERT INTO users (id, name, created_at, expires_at)
				SELECT :marker || n, 'Guest', now(), now() + make_interval(secs => :seconds)
				FROM generate_series(1, :count) AS n""")
			.param("marker", marker)
			.param("count", count)
			.param("seconds", expiresIn.toSeconds())
			.update();
	}

}
