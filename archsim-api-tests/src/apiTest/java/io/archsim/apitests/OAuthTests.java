package io.archsim.apitests;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in with GitHub against the suite's stand-in: PKCE and state checks, the session
 * cookie, accounts and their workspaces, admins, signing out and expired sessions. From
 * {@code
 * services/api/tests/test_api_oauth.py}.
 *
 * <p>
 * Not ported: {@code test_oauth_mode_needs_a_secret_a_public_url_and_a_provider} checks
 * the Python {@code Settings} class, not the API. Google sign-in cannot be faked over
 * HTTP (the Python API calls Google's real addresses), so where a Python test signs in
 * with Google for a second account, a second GitHub account stands in: what it checks
 * (accounts matched by verified email, never by an unverified one) does not depend on the
 * provider.
 */
@ApiTest(Profile.HOSTED)
class OAuthTests {

	/** The session cookie. */
	static final String SESSION = "__Host-archsim_session";

	/** The cookie that carries the signed OAuth state from the login to the callback. */
	static final String STATE = "__Host-archsim_oauth";

	/**
	 * A loopback address that is not a trusted proxy (the servers trust 127.0.0.1 only).
	 */
	private static final String UNTRUSTED = "127.0.0.2";

	@Test
	void requests_whenSignedOut_areRefused(ApiClient client) {
		assertThat(client.get("/auth/config").json())
			.isEqualTo(Json.object("mode", "oauth", "providers", Json.array("github", "google"), "sandboxes", true,
					"csrfToken", client.cookie(ApiServer.CSRF_COOKIE).orElseThrow()));
		for (ApiResponse response : List.of(client.get("/auth/me"), client.get("/projects"),
				client.post("/projects", Json.object()),
				client.post("/validate", Json.object("document", Json.object())), client.get("/workspaces"))) {
			assertThat(response.status()).as(response + ": " + response.text()).isEqualTo(401);
		}
		// public: the landing page, templates and catalog work before signing in
		assertThat(client.get("/health").status()).isEqualTo(200);
		assertThat(client.get("/examples").status()).isEqualTo(200);
		assertThat(client.get("/components").status()).isEqualTo(200);
	}

	@Test
	void login_toGitHub_redirectsWithPkceAndSignedState(ApiServer server) {
		ApiResponse response = server.client().get(ApiClient.query("/auth/github/login", "next", "/projects"));
		assertThat(response.status()).isEqualTo(302);
		Map<String, String> parameters = queryParameters(response.location());
		assertThat(page(response.location())).isEqualTo(server.github().url() + "/login/oauth/authorize");
		assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.get("code_challenge")).hasSize(43);
		assertThat(parameters.get("redirect_uri"))
			.isEqualTo(Profile.HOSTED_PUBLIC_URL + "/api/v1/auth/github/callback");
		assertThat(parameters.get("client_id")).isEqualTo(FakeGitHub.CLIENT_ID);
		assertThat(parameters.get("state")).hasSizeGreaterThanOrEqualTo(40);
		assertThat(response.setCookies()).singleElement()
			.satisfies((cookie) -> assertThat(cookie).startsWith(STATE + "=")
				.contains("HttpOnly", "Path=/", "SameSite=lax", "Secure", "Max-Age=600"));
	}

	@Test
	void signIn_firstTime_makesAccountWorkspaceAndSession(ApiServer server) {
		ApiClient client = server.client();
		FakeGitHub.Account ada = server.newAccount("ada");
		ApiResponse start = client.get(ApiClient.query("/auth/github/login", "next", "/projects/abc"));
		String back = server.github().authorize(start.location(), ada.login());
		ApiResponse done = client.open(back);
		assertThat(done.status()).isEqualTo(302);
		assertThat(done.location()).isEqualTo("/projects/abc");
		String sessionCookie = setCookie(done, SESSION);
		assertThat(sessionCookie).contains("HttpOnly", "Path=/", "SameSite=lax", "Secure");
		// the state is cleared
		assertThat(done.setCookies()).anyMatch((header) -> header.startsWith(STATE + "=\"\""));
		// the verifier went to the token endpoint, and the provider checked it against
		// the challenge
		assertVerifierSent(server, queryParameter(back, "code"));

		JsonNode me = client.get("/auth/me").json();
		assertThat(me.path("kind").asString()).isEqualTo("session");
		assertThat(me.path("user").path("name").asString()).isEqualTo("Ada");
		assertThat(me.path("user").path("email").asString()).isEqualTo(ada.email());
		assertThat(Json.items(me.path("workspaces")))
			.extracting((workspace) -> workspace.path("kind").asString() + " " + workspace.path("role").asString() + " "
					+ workspace.path("name").asString())
			.containsExactly("personal owner Ada's workspace");
		// projects go to the personal workspace; the default workspace is not hers
		client.header("X-ArchSim-CSRF", me.path("csrfToken").asString());
		JsonNode project = Runs.createProject(client);
		assertThat(client.get("/projects").json().path(0).path("id")).isEqualTo(project.path("id"));
		assertThat(client.get(ApiClient.query("/projects", "workspace", "default")).status()).isEqualTo(404);
		// only a hash of the cookie is stored
		String secret = sessionCookie.split(";", 2)[0].split("=", 2)[1];
		assertThat(server.db().column("SELECT id FROM sessions WHERE user_id = ?", Workspaces.userId(client)))
			.doesNotContain(secret)
			.hasSize(1);
	}

	/** The token request with {@code code} carried the PKCE verifier. */
	private static void assertVerifierSent(ApiServer server, String code) {
		assertThat(server.github().tokenRequests()).filteredOn((request) -> code.equals(request.get("code")))
			.singleElement()
			.satisfies((request) -> assertThat(request.get("code_verifier")).isNotEmpty());
	}

	@Test
	void signIn_again_findsTheSameAccount(ApiServer server) {
		ApiClient client = server.client();
		String ada = server.newAccount("ada").login();
		server.signIn(client, ada);
		JsonNode first = client.get("/auth/me").json();
		client.post("/auth/logout");
		server.signIn(client, ada);
		JsonNode again = client.get("/auth/me").json();
		assertThat(again.path("user").path("id")).isEqualTo(first.path("user").path("id"));
		assertThat(again.path("workspaces").size()).isEqualTo(1);
	}

	/**
	 * Google cannot be faked over HTTP: a second GitHub account with the same verified
	 * email stands in for Ada's Google account. Both are new identities matched by email
	 * alone.
	 */
	@Test
	void signIn_withSameVerifiedEmail_linksTheAccounts(ApiServer server) {
		ApiClient client = server.client();
		FakeGitHub.Account ada = server.newAccount("ada");
		server.signIn(client, ada.login());
		String github = Workspaces.userId(client);
		client.post("/auth/logout");
		FakeGitHub.Account other = account(server, "ada", ada.email(), true);
		server.signIn(client, other.login());
		assertThat(Workspaces.userId(client)).isEqualTo(github);
		assertThat(server.db().column("SELECT provider || ' ' || login FROM identities WHERE user_id = ?", github))
			.containsExactlyInAnyOrder("github " + ada.login(), "github " + other.login());
	}

	/**
	 * Mallory signs in with GitHub here (Google in the Python test): an unverified email.
	 */
	@Test
	void signIn_withUnverifiedEmail_linksAndGrantsNothing(ApiServer server) {
		ApiClient client = server.client();
		FakeGitHub.Account ada = server.newAccount("ada");
		server.signIn(client, ada.login());
		String adaId = Workspaces.userId(client);
		client.post("/auth/logout");
		server.signIn(client, account(server, "mallory", ada.email(), false).login());
		JsonNode me = client.get("/auth/me").json();
		assertThat(Json.id(me.path("user"))).isNotEqualTo(adaId);
		assertThat(me.path("user").path("email").isNull()).isTrue();
		client.post("/auth/logout");
		server.signIn(client, account(server, "fakeadmin", Profile.ADMIN_EMAIL, false).login());
		assertThat(Json.strings(client.get("/auth/me").json().path("workspaces"), "id")).doesNotContain("default");
	}

	/**
	 * On a server that outlives one test, an earlier test may have signed the admin in
	 * first: the new GitHub account is then linked to that admin by the verified email,
	 * who owns default all the same.
	 */
	@Test
	void signIn_withAdminEmail_ownsDefault(ApiServer server) {
		ApiClient client = server.client();
		server.signIn(client, account(server, "admin", Profile.ADMIN_EMAIL, true).login());
		JsonNode workspaces = client.get("/auth/me").json().path("workspaces");
		assertThat(Json.find(workspaces, "id", "default").path("role").asString()).isEqualTo("owner");
		assertThat(client.get(ApiClient.query("/projects", "workspace", "default")).status()).isEqualTo(200);
	}

	@Test
	void callback_withWrongOrMissingState_signsNobodyIn(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get("/auth/github/login");
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		String code = queryParameter(back, "code");
		String state = queryParameter(back, "state");
		String callback = "/auth/github/callback";
		ApiResponse response = client.get(ApiClient.query(callback, "code", code, "state", "not-the-state"));
		assertThat(response.location()).isEqualTo("/signin?error=state");
		// no state cookie at all (another browser, or it expired)
		client.cookies().clear();
		response = client.get(ApiClient.query(callback, "code", code, "state", state));
		assertThat(response.location()).isEqualTo("/signin?error=state");
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
		assertThat(response.setCookies()).noneMatch((header) -> header.startsWith(SESSION + "="));
	}

	@Test
	void callback_withTamperedStateCookie_isRefused(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get("/auth/github/login");
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		String[] parts = cookie(client, STATE).split("\\.", -1);
		assertThat(parts).hasSize(2);
		client.cookies().put(STATE, parts[0] + "x." + parts[1]);
		assertThat(client.open(back).location()).isEqualTo("/signin?error=state");
	}

	@Test
	void callback_whenProviderRefuses_endsOnSignInPage(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse response = client.get(ApiClient.query("/auth/github/callback", "error", "access_denied"));
		assertThat(response.location()).isEqualTo("/signin?error=denied");
		String ada = server.newAccount("ada").login();
		server.github().refuseTokensFor(ada);
		assertThat(server.signIn(client, ada)).isEqualTo("/signin?error=provider");
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
	}

	@ParameterizedTest
	@ValueSource(strings = { "//evil.example/x", "https://evil.example/", "/\\evil.example", "projects" })
	void signIn_withForeignNextPath_landsOnThisSite(String nextPath, ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get(ApiClient.query("/auth/github/login", "next", nextPath));
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		assertThat(client.open(back).location()).isEqualTo("/");
	}

	@Test
	void logout_withSessionCookie_endsTheSessionAtOnce(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String cookie = cookie(client, SESSION);
		ApiResponse response = client.post("/auth/logout");
		assertThat(response.status()).isEqualTo(204);
		assertThat(response.setCookies()).anyMatch((header) -> header.startsWith(SESSION + "=\"\""));
		// the old cookie is dead even if a browser kept it
		client.cookies().put(SESSION, cookie);
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
	}

	@Test
	void logout_everywhere_endsEverySession(ApiServer server) {
		ApiClient client = server.client();
		String ada = server.newAccount("ada").login();
		server.signIn(client, ada);
		ApiClient other = server.client();
		server.signIn(other, ada);
		assertThat(other.get("/auth/me").status()).isEqualTo(200);
		client.post(ApiClient.query("/auth/logout", "everywhere", true));
		assertThat(other.get("/auth/me").status()).isEqualTo(401);
	}

	@Test
	void session_expiredOrAccountDisabled_isSignedOut(ApiServer server) {
		ApiClient client = server.client();
		String ada = server.newAccount("ada").login();
		server.signIn(client, ada);
		String userId = Workspaces.userId(client);
		server.db().update("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
		server.signIn(client, ada);
		assertThat(client.get("/auth/me").status()).isEqualTo(200);
		server.db().update("UPDATE users SET disabled_at = now() WHERE id = ?", userId);
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
		assertThat(server.signIn(client, ada)).isEqualTo("/signin?error=disabled");
	}

	/**
	 * The servers trust {@code X-ArchSim-Client-IP} from 127.0.0.1 only. The Python test
	 * starts a second app that trusts nobody it meets; here the untrusted client is one
	 * that connects from {@value #UNTRUSTED}, so its header is ignored and its own
	 * address recorded.
	 */
	@Test
	void signIn_throughProxy_recordsClientIpOnlyIfTrusted(ApiServer server) throws IOException, InterruptedException {
		ApiClient trusted = server.client();
		server.signIn(trusted, server.newAccount("ada").login());
		assertThat(sessionIp(server, cookie(trusted, SESSION)))
			.isEqualTo(trusted.header("X-ArchSim-Client-IP").orElseThrow());

		String untrusted = LoopbackSignIn.signIn(UNTRUSTED, "203.0.113.8", server, server.newAccount("bob"));
		assertThat(sessionIp(server, untrusted)).isEqualTo(UNTRUSTED);
	}

	/**
	 * Spring Security's OAuth2 login answers before its bearer token filter, so a wrong
	 * API token starts sign-in as no token does, where it is refused elsewhere
	 * (ADR-0065).
	 */
	@Test
	void login_withRevokedToken_redirectsAsWithoutOne(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		JsonNode revoked = client.post("/tokens", Json.object("name", "CI")).expectStatus(201).json();
		assertThat(client.delete("/tokens/" + Json.id(revoked)).status()).isEqualTo(204);
		client.cookies().clear();
		client.header("X-ArchSim-CSRF", null);

		ApiResponse withToken = client.get("/auth/github/login",
				Map.of("Authorization", "Bearer " + revoked.path("token").asString()));
		ApiResponse without = client.get("/auth/github/login");

		assertThat(withToken.status()).as(withToken.text()).isEqualTo(without.status()).isEqualTo(302);
		assertThat(page(withToken.location())).isEqualTo(page(without.location()))
			.isEqualTo(server.github().url() + "/login/oauth/authorize");
	}

	/**
	 * Providers send the browser back with {@code GET}, as MVC's route takes it
	 * (ADR-0065).
	 */
	@Test
	void callback_post_isNotAllowed(ApiServer server) {
		ApiResponse response = server.client().post("/auth/github/callback");

		assertThat(response.status()).as(response.text()).isEqualTo(405);
	}

	@Test
	void auth_withoutSignIn_hasNothingToSignInTo(@On(Profile.LOCAL) ApiClient client) {
		assertThat(client.get("/auth/github/login").status()).isEqualTo(404);
		assertThat(client.post("/auth/logout").status()).isEqualTo(204);
	}

	// -- helpers

	/**
	 * A new GitHub account called {@code name} (a unique login) with any email, verified
	 * or not.
	 */
	private static FakeGitHub.Account account(ApiServer server, String name, @Nullable String email, boolean verified) {
		FakeGitHub.Account fresh = server.newAccount(name);
		return server.github().add(fresh.login(), email, verified, fresh.name());
	}

	/** A cookie the client holds; fails the test when it has none. */
	private static String cookie(ApiClient client, String name) {
		return client.cookie(name)
			.orElseThrow(() -> new AssertionError("the client holds no " + name + ": " + client.cookies().keySet()));
	}

	/**
	 * The {@code Set-Cookie} header that sets {@code name}; fails the test when there is
	 * none.
	 */
	private static String setCookie(ApiResponse response, String name) {
		return response.setCookie(name)
			.orElseThrow(() -> new AssertionError(response + " sets no " + name + ": " + response.setCookies()));
	}

	/** The page a URL names: its address without the query. */
	private static String page(String url) {
		URI uri = URI.create(url);
		return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
	}

	/** The query parameters of a URL, first value of each, as the stand-in reads them. */
	private static Map<String, String> queryParameters(String url) {
		return FakeGitHub.form(URI.create(url).getRawQuery());
	}

	private static String queryParameter(String url, String name) {
		String value = queryParameters(url).get(name);
		if (value == null) {
			throw new AssertionError(url + " has no " + name);
		}
		return value;
	}

	/**
	 * The address the session with this cookie recorded (sessions are stored by its
	 * hash).
	 */
	private static @Nullable Object sessionIp(ApiServer server, String sessionCookie) {
		return server.db().value("SELECT ip FROM sessions WHERE id = ?", sha256Hex(sessionCookie));
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
