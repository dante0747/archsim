package io.archsim.app.access;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser sessions read from Spring Security's context (ADR-0065): a public endpoint
 * neither reads nor refuses the session cookie, and credentials of another kind next to
 * it are still refused.
 */
class SessionAuthenticationTests extends HostedTestSupport {

	private static final String HEALTH = "/api/v1/health";

	@Test
	void get_publicEndpointWithExpiredSession_reachesIt() {
		SignedIn ada = signedIn("Ada");
		this.jdbc()
			.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = :user")
			.param("user", ada.userId())
			.update();

		assertThat(ada.cookie(this.mvc().get().uri(HEALTH))).hasStatusOk();
	}

	@Test
	void get_publicEndpointWithSession_leavesItUnseen() {
		SignedIn ada = signedIn("Ada");
		OffsetDateTime seen = staleSession(ada);

		ada.cookie(this.mvc().get().uri(HEALTH)).exchange();

		assertThat(lastSeen(ada)).isEqualTo(seen);
	}

	@Test
	void get_publicEndpointWithTokenAndStaleSession_leavesTheSessionUnseen() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");
		OffsetDateTime seen = staleSession(ada);

		MvcTestResult result = ada.cookie(this.mvc().get().uri(HEALTH))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(lastSeen(ada)).isEqualTo(seen);
	}

	@Test
	void get_protectedEndpointWithBasicAndStaleSession_leavesTheSessionUnseen() {
		SignedIn ada = signedIn("Ada");
		OffsetDateTime seen = staleSession(ada);

		ada.cookie(this.mvc().get().uri("/api/v1/projects"))
			.header(HttpHeaders.AUTHORIZATION, "Basic YWRhOnB3")
			.exchange();

		assertThat(lastSeen(ada)).isEqualTo(seen);
	}

	@Test
	void get_sessionWithBasicAuthorization_asksForAToken() {
		SignedIn ada = signedIn("Ada");

		assertThat(ada.cookie(this.mvc().get().uri("/api/v1/projects"))
			.header(HttpHeaders.AUTHORIZATION, "Basic YWRhOnB3")).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Send an API token as Authorization: Bearer asim_pat_...");
	}

	/**
	 * Makes a person's session last seen six minutes ago, so a request that reads it
	 * marks it seen.
	 */
	private OffsetDateTime staleSession(SignedIn person) {
		return this.jdbc()
			.sql("UPDATE sessions SET last_seen_at = now() - interval '6 minutes' WHERE user_id = :user "
					+ "RETURNING last_seen_at")
			.param("user", person.userId())
			.query(OffsetDateTime.class)
			.single();
	}

	private OffsetDateTime lastSeen(SignedIn person) {
		return this.jdbc()
			.sql("SELECT last_seen_at FROM sessions WHERE user_id = :user")
			.param("user", person.userId())
			.query(OffsetDateTime.class)
			.single();
	}

}
