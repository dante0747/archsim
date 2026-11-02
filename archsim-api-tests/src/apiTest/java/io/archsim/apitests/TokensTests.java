package io.archsim.apitests;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
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
		assertThat(Json.strings(listed, "id")).containsExactly(id(created));
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
		ApiResponse res = cli.post("/projects", Json.object("document", Examples.starter()), headers);
		assertThat(res.status()).isEqualTo(201);
		ApiResponse run = cli.post("/projects/" + id(res.json()) + "/runs", Json.object(), headers);
		assertThat(run.status()).isEqualTo(202);
		assertThat(server.db().value("SELECT last_used_at FROM api_tokens WHERE id = ?", id(token))).isNotNull();
	}

	@Test
	void request_withReadToken_onlyReads(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String project = id(Runs.createProject(client));
		JsonNode token = token(client, "scope", "read");
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		assertThat(cli.get("/projects/" + project, headers).status()).isEqualTo(200);
		for (ApiResponse res : List.of(cli.post("/projects", Json.object("document", Examples.starter()), headers),
				cli.put("/projects/" + project, Json.object("name", "x"), headers),
				cli.post("/projects/" + project + "/runs", Json.object(), headers))) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(403);
		}
		assertThat(Json.strings(cli.get("/workspaces", headers).json(), "role")).containsOnly("viewer");
	}

	@Test
	void request_withWorkspaceToken_seesOnlyItsWorkspace(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String team = id(Workspaces.create(client, "Team"));
		String inPersonal = id(Runs.createProject(client));
		JsonNode token = token(client, "workspaceId", team);
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		assertThat(cli.get("/projects/" + inPersonal, headers).status()).isEqualTo(404);
		ApiResponse res = cli.post("/projects", Json.object("document", Examples.starter()), headers);
		// into the token's workspace, the default for it
		assertThat(res.status()).isEqualTo(201);
		ApiResponse listed = cli.get("/projects",
				Map.of("Authorization", "Bearer " + secret(token), "X-ArchSim-Workspace", team));
		assertThat(Json.strings(listed.json(), "id")).containsExactly(id(res.json()));
		assertThat(Json.strings(cli.get("/workspaces", headers).json(), "id")).containsExactly(team);
	}

	@Test
	void createToken_forWorkspaceYouAreNotIn_isNotFound(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String bobs = Workspaces.first(server.signedIn("bob"));
		ApiResponse res = client.post("/tokens", Json.object("name", "x", "workspaceId", bobs));
		assertThat(res.status()).isEqualTo(404);
	}

	@Test
	void request_withRevokedExpiredOrMistypedToken_isUnauthorized(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode revoked = token(client);
		JsonNode expired = token(client);
		assertThat(client.delete("/tokens/" + id(revoked)).status()).isEqualTo(204);
		server.db().update("UPDATE api_tokens SET expires_at = now() - interval '1 second' WHERE id = ?", id(expired));
		assertThat(client.get("/tokens").json()).isEqualTo(Json.array());
		ApiClient cli = anonymous(client);
		for (String value : List.of("Bearer " + secret(revoked), "Bearer " + secret(expired),
				"Bearer asim_pat_not-a-real-token", "Bearer something-else", "Basic " + secret(revoked))) {
			ApiResponse res = cli.get("/projects", Map.of("Authorization", value));
			assertThat(res.status()).as(value).isEqualTo(401);
			assertThat(res.header("www-authenticate")).as(value).isEqualTo("Bearer");
		}
	}

	@Test
	void manageAccess_withToken_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode token = token(client, "scope", "write");
		String team = id(Workspaces.create(client, "Team"));
		ApiClient cli = anonymous(client);
		Map<String, String> headers = bearer(token);
		for (ApiResponse res : List.of(cli.post("/tokens", Json.object("name", "more"), headers),
				cli.get("/tokens", headers), cli.post("/workspaces", Json.object("name", "x"), headers),
				cli.post("/workspaces/" + team + "/invitations", Json.object(), headers),
				cli.delete("/workspaces/" + team, headers))) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(403);
		}
	}

	@Test
	void revokeToken_ofSomeoneElse_isNotFound(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode mine = token(client);
		ApiClient bob = server.signedIn("bob");
		assertThat(bob.delete("/tokens/" + id(mine)).status()).isEqualTo(404);
		assertThat(client.get("/tokens").json().size()).isEqualTo(1);
	}

	// -- CSRF

	@Test
	void change_withCookieButNoCsrfHeader_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		// the sign-in set the header, like the web app
		String project = id(Runs.createProject(client));
		String csrf = csrfHeader(client);
		client.header(CSRF, null);
		assertThat(csrf).isEqualTo(client.get("/auth/me").json().path("csrfToken").asString());
		for (ApiResponse res : List.of(client.post("/projects", Json.object("document", Examples.starter())),
				client.put("/projects/" + project, Json.object("name", "x")), client.delete("/projects/" + project),
				client.post("/projects/" + project + "/runs", Json.object()),
				client.post("/tokens", Json.object("name", "x")), client.post("/auth/logout"),
				client.post("/projects", Json.object("document", Examples.starter()), Map.of(CSRF, "guess")))) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(403);
			assertThat(res.message()).contains("X-ArchSim-CSRF");
		}
		assertThat(client.get("/projects").status()).isEqualTo(200); // reading needs none
		// the logout above did nothing
		assertThat(client.get("/auth/me").status()).isEqualTo(200);
		ApiResponse res = client.post("/projects", Json.object("document", Examples.starter()), Map.of(CSRF, csrf));
		assertThat(res.status()).isEqualTo(201);
	}

	@Test
	void csrfCookie_afterSignIn_isReadableButNotSentCrossSite(ApiServer server) {
		ApiClient browser = server.client();
		ApiResponse start = browser.get("/auth/github/login");
		ApiResponse done = browser.open(server.github().authorize(start.location(), server.newAccount("ada").login()));
		String cookie = done.setCookies()
			.stream()
			.filter((header) -> header.startsWith("__Host-archsim_csrf="))
			.findFirst()
			.orElseThrow(() -> new AssertionError(done + " sets no CSRF cookie: " + done.setCookies()));
		assertThat(cookie).doesNotContain("HttpOnly").contains("Secure", "Path=/", "SameSite=strict");
		String token = Objects.requireNonNull(browser.cookie("__Host-archsim_csrf"), "no CSRF cookie");
		ApiResponse out = browser.post("/auth/logout", null, Map.of(CSRF, token));
		assertThat(out.status()).isEqualTo(204);
		assertThat(out.setCookies()).anyMatch((header) -> header.startsWith("__Host-archsim_csrf=\"\""));
	}

	@Test
	void change_withAnotherSessionsCsrfToken_isForbidden(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String bobs = csrfHeader(server.signedIn("bob"));
		assertThat(bobs).isNotEqualTo(csrfHeader(client));
		ApiResponse res = client.post("/projects", Json.object("document", Examples.starter()), Map.of(CSRF, bobs));
		assertThat(res.status()).isEqualTo(403);
	}

	@Test
	void change_withoutSignIn_needsNoCsrfHeaderOrToken(@On(Profile.LOCAL) ApiClient client) {
		assertThat(client.post("/projects", Json.object("document", Examples.starter())).status()).isEqualTo(201);
		ApiResponse created = client.post("/tokens", Json.object("name", "local"));
		// possible, though only a signed-in server checks it
		assertThat(created.status()).isEqualTo(201);
		// everyone is the same person without sign-in, and a person keeps 50 tokens at
		// most: revoke it, so a server that outlives this run does not fill up
		client.delete("/tokens/" + id(created.json()));
	}

	// -- helpers

	/**
	 * A new token named CI with {@code body}'s other fields; fails the test unless it is
	 * made.
	 */
	private static JsonNode token(ApiClient client, @Nullable Object... body) {
		ObjectNode json = Json.object("name", "CI");
		json.setAll(Json.object(body));
		ApiResponse res = client.post("/tokens", json);
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json();
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
		return Objects.requireNonNull(client.header(CSRF), "the client sends no CSRF header");
	}

	private static String secret(JsonNode token) {
		return token.path("token").asString();
	}

	private static String id(JsonNode created) {
		return created.path("id").asString();
	}

}
