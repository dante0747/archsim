package io.archsim.app.tokens;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Secrets;
import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.SameInstant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal API tokens: shown once when made, stored as a hash, managed in person and
 * never with a token.
 */
class TokensTests extends HostedTestSupport {

	private static final String TOKENS = "/api/v1/tokens";

	@Test
	void create_writeToken_isShownOnceWithItsPrefix() {
		SignedIn ada = signedIn("Ada");

		JsonNode created = created(create(ada, "{\"name\": \"CI\"}"));

		assertThat(created.path("token").asString()).startsWith("asim_pat_").hasSize(52);
		assertThat(created.path("prefix").asString()).isEqualTo(created.path("token").asString().substring(0, 13));
		assertThat(created.path("scope").asString()).isEqualTo("write");
	}

	@Test
	void create_token_isStoredAsItsHash() {
		SignedIn ada = signedIn("Ada");

		JsonNode created = created(create(ada, "{\"name\": \"CI\"}"));

		assertThat(this.jdbc()
			.sql("SELECT token_hash FROM api_tokens WHERE id = :id")
			.param("id", created.path("id").asString())
			.query(String.class)
			.single()).isEqualTo(Secrets.sha256Hex(created.path("token").asString()));
	}

	@Test
	void create_byDefault_expiresInNinetyDays() {
		SignedIn ada = signedIn("Ada");

		JsonNode created = created(create(ada, "{\"name\": \"CI\"}"));

		assertThat(created.path("expiresAt").asString()).isNotEmpty();
	}

	@Test
	void create_withoutExpiry_neverExpires() {
		SignedIn ada = signedIn("Ada");

		JsonNode created = created(create(ada, "{\"name\": \"CI\", \"expiresInDays\": null}"));

		assertThat(created.path("expiresAt").isNull()).isTrue();
	}

	@Test
	void create_blankName_isNamedToken() {
		SignedIn ada = signedIn("Ada");

		JsonNode created = created(create(ada, "{\"name\": \"   \"}"));

		assertThat(created.path("name").asString()).isEqualTo("token");
	}

	@Test
	void create_unknownScope_isRefusedAsPydanticRefusesIt() {
		SignedIn ada = signedIn("Ada");

		assertThat(create(ada, "{\"name\": \"CI\", \"scope\": \"admin\"}")).hasStatus(422);
	}

	@Test
	void create_withAToken_isForbidden() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");

		MvcTestResult result = this.mvc()
			.post()
			.uri(TOKENS)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		assertThat(result).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("API tokens cannot do this: sign in to the web app instead.");
	}

	@Test
	void create_forAWorkspaceYouAreNotIn_isNotFound() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");

		assertThat(create(ada, "{\"name\": \"CI\", \"workspaceId\": \"" + bob.workspaceId() + "\"}")).hasStatus(404);
	}

	@Test
	void create_withFiftyActive_isAConflict() {
		SignedIn ada = signedIn("Ada");
		for (int i = 0; i < 50; i++) {
			token(ada.userId(), null, "read");
		}

		assertThat(create(ada, "{\"name\": \"CI\"}")).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("You have 50 tokens: revoke some first.");
	}

	@Test
	void create_twentyFirstToday_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		for (int i = 0; i < 20; i++) {
			create(ada, "{\"name\": \"CI\"}");
		}

		assertThat(create(ada, "{\"name\": \"CI\"}")).hasStatus(429).containsHeader(HttpHeaders.RETRY_AFTER);
	}

	@Test
	void list_tokens_areTheActiveOnesWithoutTheirSecrets() {
		SignedIn ada = signedIn("Ada");
		String revoked = created(create(ada, "{\"name\": \"old\"}")).path("id").asString();
		ada.browser(this.mvc().delete().uri(TOKENS + "/" + revoked)).exchange();
		String kept = created(create(ada, "{\"name\": \"new\"}")).path("id").asString();

		MvcTestResult listed = ada.cookie(this.mvc().get().uri(TOKENS)).exchange();

		assertThat(listed).bodyJson().extractingPath("$[*].id").asArray().containsExactly(kept);
		assertThat(listed).bodyJson().extractingPath("$[0]").asMap().doesNotContainKey("token");
	}

	@Test
	void list_tokensMadeAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeTokenAtNine(id, ada.userId()));

		JsonNode listed = json(ada.cookie(this.mvc().get().uri(TOKENS)).exchange());

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
	}

	@Test
	void revoke_someoneElsesToken_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String adas = created(create(ada, "{\"name\": \"CI\"}")).path("id").asString();
		SignedIn bob = signedIn("Bob");

		assertThat(bob.browser(this.mvc().delete().uri(TOKENS + "/" + adas))).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Token not found");
	}

	@Test
	void revoke_ownToken_stopsItWorking() {
		SignedIn ada = signedIn("Ada");
		JsonNode created = created(create(ada, "{\"name\": \"CI\"}"));

		ada.browser(this.mvc().delete().uri(TOKENS + "/" + created.path("id").asString())).exchange();

		assertThat(this.mvc()
			.get()
			.uri("/api/v1/projects")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + created.path("token").asString())).hasStatus(401);
	}

	private MvcTestResult create(SignedIn person, String body) {
		return person.browser(this.mvc().post().uri(TOKENS))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private void storeTokenAtNine(String id, String userId) {
		this.jdbc()
			.sql("""
					INSERT INTO api_tokens (id, user_id, name, prefix, token_hash, scope, created_at)
					VALUES (:id, :user, 'CI', 'asim_pat_test', :hash, 'read', :at)""")
			.param("id", id)
			.param("user", userId)
			.param("hash", Secrets.sha256Hex(id))
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

}
