package io.archsim.apitests;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal API tokens (Bearer) and the CSRF header that cookie requests need. From {@code
 * services/api/tests/test_api_tokens.py}, whose tests start signed in as Ada: here each
 * signs in a new Ada ({@link ApiServer#signedIn}).
 */
@ApiTest(Profile.HOSTED)
class TokensTests {

	private static final String CSRF = "X-ArchSim-CSRF";

	// -- tokens

	@Test
	void createToken_withWriteScope_isShownOnceAndStoredAsHash(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode created = token(client, "scope", "write");
		String secret = secret(created);
		assertThat(secret).startsWith("asim_pat_").hasSizeGreaterThan(40);
		assertThat(created.path("prefix").asString()).isEqualTo(secret.substring(0, 13));
		JsonNode listed = client.get("/tokens").json();
		assertThat(Json.strings(listed, "id")).containsExactly(Json.id(created));
		assertThat(listed.path(0).has("token")).isFalse();
		assertThat(server.db().column("SELECT token_hash FROM api_tokens WHERE user_id = ?", Workspaces.userId(client)))
			.singleElement(InstanceOfAssertFactories.STRING)
			.isNotEqualTo(secret)
			.doesNotContain(secret);
	}

	@Test
	void request_withWriteToken_actsAsItsOwnerWithoutCookiesOrCsrf(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode token = token(client, "scope", "write");
		JsonNode mine = client.get("/auth/me").json();
		ApiClient cli = anonymous(client);
		assertThat(cli.get("/projects").status()).isEqualTo(401);
		Map<String, String> headers = bearer(token);
		JsonNode me = cli.get("/auth/me", headers).json();
		assertThat(me.path("kind").asString()).isEqualTo("token");
		assertThat(me.path("user").path("id")).isEqualTo(mine.path("user").path("id"));
		assertThat(me.path("csrfToken").isNull()).as(me.toString()).isTrue();
		ApiResponse response = cli.post("/projects", Json.object("document", Examples.starter()), headers);
		assertThat(response.status()).isEqualTo(201);
		ApiResponse run = cli.post("/projects/" + Json.id(response.json()) + "/runs", Json.object(), headers);
		assertThat(run.status()).isEqualTo(202);
		assertThat(server.db().value("SELECT last_used_at FROM api_tokens WHERE id = ?", Json.id(token))).isNotNull();
	}

	@Test
	void request_withReadToken_onlyReads(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String project = Json.id(Runs.createProject(client));
		JsonNode token = token(client, "scope", "read");
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		assertThat(cli.get("/projects/" + project, headers).status()).isEqualTo(200);
		for (ApiResponse response : List.of(cli.post("/projects", Json.object("document", Examples.starter()), headers),
				cli.put("/projects/" + project, Json.object("name", "x"), headers),
				cli.post("/projects/" + project + "/runs", Json.object(), headers))) {
			assertThat(response.status()).as(response + ": " + response.text()).isEqualTo(403);
		}
		assertThat(Json.strings(cli.get("/workspaces", headers).json(), "role")).containsOnly("viewer");
	}

	@Test
	void request_withWorkspaceToken_seesOnlyItsWorkspace(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String team = Json.id(Workspaces.create(client, "Team"));
		String inPersonal = Json.id(Runs.createProject(client));
		JsonNode token = token(client, "workspaceId", team);
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		assertThat(cli.get("/projects/" + inPersonal, headers).status()).isEqualTo(404);
		ApiResponse response = cli.post("/projects", Json.object("document", Examples.starter()), headers);
		// into the token's workspace, the default for it
		assertThat(response.status()).isEqualTo(201);
		ApiResponse listed = cli.get("/projects",
				Map.of("Authorization", "Bearer " + secret(token), "X-ArchSim-Workspace", team));
		assertThat(Json.strings(listed.json(), "id")).containsExactly(Json.id(response.json()));
		assertThat(Json.strings(cli.get("/workspaces", headers).json(), "id")).containsExactly(team);
	}

	@Test
	void createToken_forWorkspaceYouAreNotIn_isNotFound(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String bobs = Workspaces.first(server.signedIn("bob"));
		ApiResponse response = client.post("/tokens", Json.object("name", "x", "workspaceId", bobs));
		assertThat(response.status()).isEqualTo(404);
	}

	@Test
	void request_withRevokedExpiredOrMistypedToken_isUnauthorized(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode revoked = token(client);
		JsonNode expired = token(client);
		assertThat(client.delete("/tokens/" + Json.id(revoked)).status()).isEqualTo(204);
		server.db()
			.update("UPDATE api_tokens SET expires_at = now() - interval '1 second' WHERE id = ?", Json.id(expired));
		assertThat(client.get("/tokens").json()).isEqualTo(Json.array());
		ApiClient cli = anonymous(client);
		for (String value : List.of("Bearer " + secret(revoked), "Bearer " + secret(expired),
				"Bearer asim_pat_not-a-real-token", "Bearer something-else", "Basic " + secret(revoked))) {
			ApiResponse response = cli.get("/projects", Map.of("Authorization", value));
			assertThat(response.status()).as(value).isEqualTo(401);
			assertThat(response.header("www-authenticate")).as(value).hasValue("Bearer");
		}
	}

	@Test
	void manageAccess_withToken_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode token = token(client, "scope", "write");
		String team = Json.id(Workspaces.create(client, "Team"));
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		for (ApiResponse response : List.of(cli.post("/tokens", Json.object("name", "more"), headers),
				cli.get("/tokens", headers), cli.post("/workspaces", Json.object("name", "x"), headers),
				cli.post("/workspaces/" + team + "/invitations", Json.object(), headers),
				cli.delete("/workspaces/" + team, headers))) {
			assertThat(response.status()).as(response + ": " + response.text()).isEqualTo(403);
		}
	}

	@Test
	void revokeToken_ofSomeoneElse_isNotFound(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode mine = token(client);
		ApiClient bob = server.signedIn("bob");
		assertThat(bob.delete("/tokens/" + Json.id(mine)).status()).isEqualTo(404);
		assertThat(client.get("/tokens").json().size()).isEqualTo(1);
	}

	// -- a token is read wherever it is sent (ADR-0065)

	@ParameterizedTest
	@ValueSource(strings = { "/health", "/public/shares/no-such-share", "/nothing-here" })
	void request_withRevokedToken_isUnauthorizedAsOnAProtectedEndpoint(String path, ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode revoked = token(client);
		assertThat(client.delete("/tokens/" + Json.id(revoked)).status()).isEqualTo(204);
		assertRefusedAsOnAProtectedEndpoint(anonymous(client), path, "Bearer " + secret(revoked));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/health", "/public/shares/no-such-share", "/nothing-here" })
	void request_withExpiredToken_isUnauthorizedAsOnAProtectedEndpoint(String path, ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode expired = token(client);
		server.db()
			.update("UPDATE api_tokens SET expires_at = now() - interval '1 second' WHERE id = ?", Json.id(expired));
		assertRefusedAsOnAProtectedEndpoint(anonymous(client), path, "Bearer " + secret(expired));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/health", "/public/shares/no-such-share", "/nothing-here" })
	void request_withMalformedToken_isUnauthorizedAsOnAProtectedEndpoint(String path, ApiServer server) {
		assertRefusedAsOnAProtectedEndpoint(server.client(), path, "Bearer not a token");
	}

	@ParameterizedTest
	@CsvSource({ "/health,200", "/public/shares/no-such-share,404", "/nothing-here,404" })
	void request_withoutToken_reachesTheEndpoint(String path, int status, ApiServer server) {
		assertThat(server.client().get(path).status()).isEqualTo(status);
	}

	@ParameterizedTest
	@CsvSource({ "/health,200", "/public/shares/no-such-share,404", "/nothing-here,404" })
	void request_withBasicCredentials_reachesTheEndpoint(String path, int status, ApiServer server) {
		ApiResponse response = server.client().get(path, Map.of("Authorization", "Basic YWRhOnB3"));
		assertThat(response.status()).isEqualTo(status);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/.well-known/oauth-protected-resource/api/v1", "/.well-known/oauth-protected-resource" })
	void protectedResourceMetadata_withSignIn_describesTheApi(String path, ApiServer server) {
		ApiResponse response = server.client().open(path);
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json()).isEqualTo(Json.object("resource", Profile.HOSTED_PUBLIC_URL + "/api/v1",
				"bearer_methods_supported", Json.array("header"), "tls_client_certificate_bound_access_tokens", false));
	}

	// -- CSRF

	@Test
	void change_withCookieButNoCsrfHeader_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		// the sign-in set the header, like the web app
		String project = Json.id(Runs.createProject(client));
		String csrf = csrfHeader(client);
		client.header(CSRF, null);
		assertThat(csrf).isEqualTo(client.get("/auth/me").json().path("csrfToken").asString());
		for (ApiResponse response : List.of(client.post("/projects", Json.object("document", Examples.starter())),
				client.put("/projects/" + project, Json.object("name", "x")), client.delete("/projects/" + project),
				client.post("/projects/" + project + "/runs", Json.object()),
				client.post("/tokens", Json.object("name", "x")), client.post("/auth/logout"),
				client.post("/projects", Json.object("document", Examples.starter()), Map.of(CSRF, "guess")))) {
			assertThat(response.status()).as(response + ": " + response.text()).isEqualTo(403);
			assertThat(response.message()).contains("X-ArchSim-CSRF");
		}
		assertThat(client.get("/projects").status()).isEqualTo(200); // reading needs none
		// the logout above did nothing
		assertThat(client.get("/auth/me").status()).isEqualTo(200);
		ApiResponse response = client.post("/projects", Json.object("document", Examples.starter()),
				Map.of(CSRF, csrf));
		assertThat(response.status()).isEqualTo(201);
	}

	@Test
	void csrfCookie_afterSignIn_isReadableButNotSentCrossSite(ApiServer server) {
		ApiClient browser = server.client();
		ApiResponse start = browser.get("/auth/github/login");
		ApiResponse done = browser.open(server.github().authorize(start.location(), server.newAccount("ada").login()));
		String cookie = done.setCookie("__Host-archsim_csrf")
			.orElseThrow(() -> new AssertionError(done + " sets no CSRF cookie: " + done.setCookies()));
		assertThat(cookie).doesNotContain("HttpOnly").contains("Secure", "Path=/", "SameSite=strict");
		String token = browser.cookie("__Host-archsim_csrf").orElseThrow(() -> new AssertionError("no CSRF cookie"));
		ApiResponse out = browser.post("/auth/logout", null, Map.of(CSRF, token));
		assertThat(out.status()).isEqualTo(204);
		assertThat(out.setCookies()).anyMatch((header) -> header.startsWith("__Host-archsim_csrf=\"\""));
	}

	@Test
	void change_withAnotherSessionsCsrfToken_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String bobs = csrfHeader(server.signedIn("bob"));
		assertThat(bobs).isNotEqualTo(csrfHeader(client));
		ApiResponse response = client.post("/projects", Json.object("document", Examples.starter()),
				Map.of(CSRF, bobs));
		assertThat(response.status()).isEqualTo(403);
	}

	@Test
	void change_withoutSignIn_needsNoCsrfHeaderOrToken(@On(Profile.LOCAL) ApiClient client) {
		assertThat(client.post("/projects", Json.object("document", Examples.starter())).status()).isEqualTo(201);
		ApiResponse created = client.post("/tokens", Json.object("name", "local"));
		// possible, though only a signed-in server checks it
		assertThat(created.status()).isEqualTo(201);
		// everyone is the same person without sign-in, and a person keeps 50 tokens at
		// most: revoke it, so a server that outlives this run does not fill up
		client.delete("/tokens/" + Json.id(created.json()));
	}

	// -- helpers

	/**
	 * Checks a wrong token's answer on a path: the {@code 401} a protected endpoint gives
	 * it, header and body alike.
	 * @param client a client without cookies
	 * @param path where the token is sent
	 * @param authorization the wrong {@code Authorization} header
	 */
	private static void assertRefusedAsOnAProtectedEndpoint(ApiClient client, String path, String authorization) {
		Map<String, String> headers = Map.of("Authorization", authorization);
		ApiResponse onProtected = client.get("/projects", headers);
		ApiResponse response = client.get(path, headers);
		assertThat(response.status()).as(response.text()).isEqualTo(401);
		assertThat(response.header("www-authenticate")).hasValue("Bearer");
		assertThat(response.json()).isEqualTo(onProtected.json());
	}

	/**
	 * A new token named CI with {@code body}'s other fields; fails the test unless it is
	 * made.
	 */
	private static JsonNode token(ApiClient client, @Nullable Object... body) {
		ObjectNode json = Json.object("name", "CI");
		json.setAll(Json.object(body));
		return client.post("/tokens", json).expectStatus(201).json();
	}

	private static Map<String, String> bearer(JsonNode token) {
		return Map.of("Authorization", "Bearer " + secret(token));
	}

	/**
	 * The same client without the browser's cookies and CSRF header (as a CLI would
	 * call).
	 */
	private static ApiClient anonymous(ApiClient client) {
		client.cookies().clear();
		client.header(CSRF, null);
		return client;
	}

	/**
	 * The CSRF header a signed-in client sends (the sign-in set it, as the web app does).
	 */
	private static String csrfHeader(ApiClient client) {
		return client.header(CSRF).orElseThrow(() -> new AssertionError("the client sends no CSRF header"));
	}

	private static String secret(JsonNode token) {
		return token.path("token").asString();
	}

}
