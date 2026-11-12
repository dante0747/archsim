package io.archsim.app.access;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal API tokens, read by Spring Security's bearer token filter wherever they are
 * sent: a wrong one is refused before the API looks at the path, and a request without
 * one, or with credentials of another kind, reaches the endpoint (ADR-0065).
 */
class TokenAuthenticationTests extends HostedTestSupport {

	private static final String HEALTH = "/api/v1/health";

	private static final String NOTHING_HERE = "/api/v1/nothing-here";

	/** The origin a browser may call the API from: ARCHSIM_CORS_ORIGINS' default. */
	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private static final String METADATA = """
			{"resource": "https://archsim.test/api/v1", "bearer_methods_supported": ["header"],
			 "tls_client_certificate_bound_access_tokens": false}""";

	@Test
	void get_publicEndpointWithRevokedToken_isUnauthorized() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");
		this.jdbc()
			.sql("UPDATE api_tokens SET revoked_at = now() WHERE user_id = :user")
			.param("user", ada.userId())
			.update();

		assertThat(this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatus(401)
			.hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This API token is not valid: it was revoked, expired or mistyped");
	}

	@Test
	void get_unknownPathWithMalformedToken_asksForAToken() {
		assertThat(this.mvc().get().uri(NOTHING_HERE).header(HttpHeaders.AUTHORIZATION, "Bearer not a token"))
			.hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Send an API token as Authorization: Bearer asim_pat_...");
	}

	@Test
	void get_withTokenWithoutOurPrefix_asksForAToken() {
		assertThat(this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer something-else"))
			.hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Send an API token as Authorization: Bearer asim_pat_...");
	}

	@Test
	void get_withTokenOfDisabledAccount_isRefused() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");
		this.jdbc().sql("UPDATE users SET disabled_at = now() WHERE id = :user").param("user", ada.userId()).update();

		assertThat(this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatus(401)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This account cannot sign in");
	}

	@Test
	void get_withTokenUsedWithinTheMinute_keepsItsLastUse() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");
		OffsetDateTime used = this.jdbc()
			.sql("UPDATE api_tokens SET last_used_at = now() - interval '30 seconds' WHERE user_id = :user "
					+ "RETURNING last_used_at")
			.param("user", ada.userId())
			.query(OffsetDateTime.class)
			.single();

		this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

		assertThat(this.jdbc()
			.sql("SELECT last_used_at FROM api_tokens WHERE user_id = :user")
			.param("user", ada.userId())
			.query(OffsetDateTime.class)
			.single()).isEqualTo(used);
	}

	@Test
	void get_publicEndpointWithValidToken_reachesIt() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");

		assertThat(this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatusOk();
	}

	@Test
	void get_unknownPathWithValidToken_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "read");

		assertThat(this.mvc().get().uri(NOTHING_HERE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.hasStatus(404);
	}

	@Test
	void get_publicEndpointWithBasicAuthorization_reachesIt() {
		assertThat(this.mvc().get().uri(HEALTH).header(HttpHeaders.AUTHORIZATION, "Basic YWRhOnB3")).hasStatusOk();
	}

	@Test
	void get_wrongTokenFromAllowedOrigin_letsTheBrowserReadTheRefusal() {
		assertThat(this.mvc()
			.get()
			.uri(HEALTH)
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.AUTHORIZATION, "Bearer asim_pat_unknown")).hasStatus(401)
			.hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/.well-known/oauth-protected-resource/api/v1", "/.well-known/oauth-protected-resource" })
	void get_protectedResourceMetadata_describesTheApi(String path) {
		assertThat(this.mvc().get().uri(path)).hasStatusOk().bodyJson().isStrictlyEqualTo(METADATA);
	}

}
