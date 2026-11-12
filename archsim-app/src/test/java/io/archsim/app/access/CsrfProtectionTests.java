package io.archsim.app.access;

import java.util.Objects;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSRF through Spring Security's filter (ADR-0065): a change a browser session
 * authenticates must send its CSRF cookie's value in {@code X-ArchSim-CSRF}, wherever it
 * goes, and the web app finds the value at {@code /auth/me}.
 */
class CsrfProtectionTests extends HostedTestSupport {

	private static final String NOTHING_HERE = "/api/v1/nothing-here";

	private static final String PROJECTS = "/api/v1/projects";

	@Test
	void post_unknownPathWithSessionButNoCsrfHeader_isForbidden() {
		SignedIn ada = signedIn("Ada");

		assertThat(ada.cookie(this.mvc().post().uri(NOTHING_HERE))).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.contains("X-ArchSim-CSRF");
	}

	@Test
	void post_unknownPathWithoutSession_isNotFound() {
		assertThat(this.mvc().post().uri(NOTHING_HERE)).hasStatus(404);
	}

	@Test
	void post_withHeaderButNoCsrfCookie_isForbidden() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.cookie(this.mvc().post().uri(PROJECTS))
			.header(BrowserCsrf.HEADER, ada.csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(403);
	}

	@Test
	void post_withNonAsciiCsrfHeader_isForbidden() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.cookie(this.mvc().post().uri(PROJECTS))
			.cookie(new Cookie(BrowserCsrf.COOKIE, ada.csrf()))
			.header(BrowserCsrf.HEADER, "été")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(403);
	}

	@Test
	void post_withTokenAndSessionButNoCsrfHeader_isCreated() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");

		MvcTestResult result = ada.cookie(this.mvc().post().uri(PROJECTS))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(201);
	}

	@Test
	void post_withExpiredSessionAndNoCsrfHeader_asksToSignInAgain() {
		SignedIn ada = signedIn("Ada");
		expire(ada);

		assertThat(ada.cookie(this.mvc().post().uri(PROJECTS)).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Your sign-in has expired: sign in again");
	}

	@Test
	void logoutEverywhere_withExpiredSessionAndNoCsrfHeader_endsNoOtherSession() {
		SignedIn laptop = signedIn("Ada");
		SignedIn phone = session(laptop.userId(), laptop.workspaceId());
		expire(laptop);

		laptop.cookie(this.mvc().post().uri("/api/v1/auth/logout?everywhere=true")).exchange();

		assertThat(phone.cookie(this.mvc().get().uri("/api/v1/auth/me"))).hasStatusOk();
	}

	@Test
	void me_withoutCsrfCookie_handsOutANewToken() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.cookie(this.mvc().get().uri("/api/v1/auth/me")).exchange();

		Cookie csrf = Objects.requireNonNull(result.getResponse().getCookie(BrowserCsrf.COOKIE), "no CSRF cookie");
		assertThat(result).bodyJson().extractingPath("$.csrfToken").isEqualTo(csrf.getValue());
	}

	@Test
	void me_withoutCsrfCookie_setsAHostCookieOtherSitesNeverSend() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.cookie(this.mvc().get().uri("/api/v1/auth/me")).exchange();

		Cookie csrf = Objects.requireNonNull(result.getResponse().getCookie(BrowserCsrf.COOKIE), "no CSRF cookie");
		assertThat(csrf.getSecure()).isTrue();
		assertThat(csrf.getPath()).isEqualTo("/");
		assertThat(csrf.getDomain()).isNull();
		assertThat(csrf.isHttpOnly()).isFalse();
		assertThat(csrf.getAttribute("SameSite")).isEqualTo("strict");
	}

	@Test
	void me_withToken_handsOutNoCsrfToken() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");

		MvcTestResult result = this.mvc()
			.get()
			.uri("/api/v1/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.exchange();

		assertThat(result).bodyJson().extractingPath("$.csrfToken").isNull();
		assertThat(result.getResponse().getCookie(BrowserCsrf.COOKIE)).isNull();
	}

	private void expire(SignedIn person) {
		this.jdbc()
			.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE id = :id")
			.param("id", Secrets.sha256Hex(person.cookie()))
			.update();
	}

}
