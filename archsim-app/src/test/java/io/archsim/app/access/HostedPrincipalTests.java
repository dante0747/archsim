package io.archsim.app.access;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is asking on a server with sign-in: a browser session's cookie, with the CSRF
 * header on every change, or a personal API token; nobody else.
 */
class HostedPrincipalTests extends HostedTestSupport {

	private static final String PROJECTS = "/api/v1/projects";

	@Test
	void get_withoutCookieOrToken_asksToSignIn() {
		assertThat(this.mvc().get().uri(PROJECTS)).hasStatus(401)
			.hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign in to continue");
	}

	@Test
	void get_withUnknownCookie_saysTheSignInExpired() {
		SignedIn nobody = new SignedIn("nobody", "nowhere", "not-a-session", "");

		assertThat(nobody.cookie(this.mvc().get().uri(PROJECTS))).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Your sign-in has expired: sign in again");
	}

	@Test
	void get_withExpiredSession_saysTheSignInExpired() {
		SignedIn ada = signedIn("Ada");
		this.jdbc()
			.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = :user")
			.param("user", ada.userId())
			.update();

		assertThat(ada.cookie(this.mvc().get().uri(PROJECTS))).hasStatus(401);
	}

	@Test
	void get_ofDisabledAccount_isRefused() {
		SignedIn ada = signedIn("Ada");
		this.jdbc().sql("UPDATE users SET disabled_at = now() WHERE id = :user").param("user", ada.userId()).update();

		assertThat(ada.cookie(this.mvc().get().uri(PROJECTS))).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This account cannot sign in");
	}

	@Test
	void get_withSession_readsWithoutCsrfHeader() {
		SignedIn ada = signedIn("Ada");

		assertThat(ada.cookie(this.mvc().get().uri(PROJECTS))).hasStatusOk();
	}

	@Test
	void get_withSessionNotSeenForAWhile_marksItSeen() {
		SignedIn ada = signedIn("Ada");
		this.jdbc()
			.sql("UPDATE sessions SET last_seen_at = now() - interval '6 minutes' WHERE user_id = :user")
			.param("user", ada.userId())
			.update();

		ada.cookie(this.mvc().get().uri(PROJECTS)).exchange();

		assertThat(lastSeen("sessions", "user_id", ada.userId())).isAfter(Instant.now().minusSeconds(60));
		assertThat(lastSeen("users", "id", ada.userId())).isAfter(Instant.now().minusSeconds(60));
	}

	@Test
	void post_withSessionButNoCsrfHeader_isForbidden() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.cookie(this.mvc().post().uri(PROJECTS))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.contains("X-ArchSim-CSRF");
	}

	@Test
	void post_withSessionAndCsrfHeader_createsInThePersonalWorkspace() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = ada.browser(this.mvc().post().uri(PROJECTS))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(201);
		assertThat(this.jdbc()
			.sql("SELECT workspace_id FROM projects WHERE created_by = :user")
			.param("user", ada.userId())
			.query(String.class)
			.single()).isEqualTo(ada.workspaceId());
	}

	@Test
	void post_withWriteToken_needsNoCsrfHeader() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");

		MvcTestResult result = this.mvc()
			.post()
			.uri(PROJECTS)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(201);
	}

	@Test
	void get_withToken_marksItUsed() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");

		this.mvc().get().uri(PROJECTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

		assertThat(this.jdbc()
			.sql("SELECT last_used_at FROM api_tokens WHERE token_hash = :hash")
			.param("hash", Secrets.sha256Hex(token))
			.query(OffsetDateTime.class)
			.single()).isNotNull();
	}

	@Test
	void get_withBasicAuthorization_asksForAToken() {
		assertThat(this.mvc().get().uri(PROJECTS).header(HttpHeaders.AUTHORIZATION, "Basic YWRhOnB3")).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Send an API token as Authorization: Bearer asim_pat_...");
	}

	@Test
	void get_withRevokedToken_isRefused() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");
		this.jdbc()
			.sql("UPDATE api_tokens SET revoked_at = now() WHERE user_id = :user")
			.param("user", ada.userId())
			.update();

		assertThat(this.mvc().get().uri(PROJECTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This API token is not valid: it was revoked, expired or mistyped");
	}

	private Instant lastSeen(String table, String column, String userId) {
		return this.jdbc()
			.sql("SELECT last_seen_at FROM " + table + " WHERE " + column + " = :user")
			.param("user", userId)
			.query(OffsetDateTime.class)
			.single()
			.toInstant();
	}

}
