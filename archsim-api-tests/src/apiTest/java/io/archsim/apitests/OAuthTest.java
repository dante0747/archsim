package io.archsim.apitests;

import static io.archsim.apitests.ApiClient.query;
import static io.archsim.apitests.Json.items;
import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

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
class OAuthTest {

	private static final String SESSION = "__Host-archsim_session";

	private static final String STATE = "__Host-archsim_oauth";

	/**
	 * A loopback address that is not a trusted proxy (the servers trust 127.0.0.1 only).
	 */
	private static final String UNTRUSTED = "127.0.0.2";

	@Test
	void signedOutRequestsAreRefused(ApiClient client) {
		assertThat(client.get("/auth/config").json())
			.isEqualTo(object("mode", "oauth", "providers", Json.array("github", "google")));
		for (ApiResponse res : List.of(client.get("/auth/me"), client.get("/projects"),
				client.post("/projects", object()), client.post("/validate", object("document", object())),
				client.get("/workspaces"))) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(401);
		}
		// public: the landing page, templates and catalog work before signing in
		assertThat(client.get("/health").status()).isEqualTo(200);
		assertThat(client.get("/examples").status()).isEqualTo(200);
		assertThat(client.get("/components").status()).isEqualTo(200);
	}

	@Test
	void loginRedirectsToTheProviderWithPkceAndASignedState(ApiServer server) {
		ApiResponse res = server.client().get(query("/auth/github/login", "next", "/projects"));
		assertThat(res.status()).isEqualTo(302);
		URI url = URI.create(res.location());
		Map<String, String> q = params(res.location());
		assertThat(url.getScheme() + "://" + url.getRawAuthority() + url.getRawPath())
			.isEqualTo(server.github().url() + "/login/oauth/authorize");
		assertThat(q.get("code_challenge_method")).isEqualTo("S256");
		assertThat(q.get("code_challenge")).hasSize(43);
		assertThat(q.get("redirect_uri")).isEqualTo(Profile.HOSTED_PUBLIC_URL + "/api/v1/auth/github/callback");
		assertThat(q.get("client_id")).isEqualTo(FakeGitHub.CLIENT_ID);
		assertThat(q.get("state")).hasSizeGreaterThanOrEqualTo(40);
		assertThat(res.setCookies()).singleElement()
			.satisfies(cookie -> assertThat(cookie).startsWith(STATE + "=")
				.contains("HttpOnly", "Path=/", "SameSite=lax", "Secure", "Max-Age=600"));
	}

	@Test
	void signingInMakesAnAccountAPersonalWorkspaceAndASession(ApiServer server) {
		ApiClient client = server.client();
		FakeGitHub.Account ada = server.newAccount("ada");
		ApiResponse start = client.get(query("/auth/github/login", "next", "/projects/abc"));
		String back = server.github().authorize(start.location(), ada.login());
		ApiResponse done = client.open(back);
		assertThat(done.status()).isEqualTo(302);
		assertThat(done.location()).isEqualTo("/projects/abc");
		String sessionCookie = setCookie(done, SESSION);
		assertThat(sessionCookie).contains("HttpOnly", "Path=/", "SameSite=lax", "Secure");
		assertThat(done.setCookies()).anyMatch(c -> c.startsWith(STATE + "=\"\"")); // state
																					// cleared
		// the verifier went to the token endpoint, and the provider checked it against
		// the challenge
		String code = param(back, "code");
		assertThat(server.github().tokenRequests()).filteredOn(r -> code.equals(r.get("code")))
			.singleElement()
			.satisfies(r -> assertThat(r.get("code_verifier")).isNotEmpty());

		JsonNode me = client.get("/auth/me").json();
		assertThat(me.path("kind").asString()).isEqualTo("session");
		assertThat(me.path("user").path("name").asString()).isEqualTo("Ada");
		assertThat(me.path("user").path("email").asString()).isEqualTo(ada.email());
		assertThat(items(me.path("workspaces")))
			.extracting(
					w -> w.path("kind").asString() + " " + w.path("role").asString() + " " + w.path("name").asString())
			.containsExactly("personal owner Ada's workspace");
		// projects go to the personal workspace; the default workspace is not hers
		client.header("X-ArchSim-CSRF", me.path("csrfToken").asString());
		JsonNode p = Runs.createProject(client);
		assertThat(client.get("/projects").json().path(0).path("id")).isEqualTo(p.path("id"));
		assertThat(client.get(query("/projects", "workspace", "default")).status()).isEqualTo(404);
		// only a hash of the cookie is stored
		String secret = sessionCookie.split(";", 2)[0].split("=", 2)[1];
		assertThat(server.db().column("SELECT id FROM sessions WHERE user_id = ?", Workspaces.userId(client)))
			.doesNotContain(secret)
			.hasSize(1);
	}

	@Test
	void signingInAgainFindsTheSameAccount(ApiServer server) {
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
	void aVerifiedEmailLinksTwoSignInAccounts(ApiServer server) {
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
	void anUnverifiedEmailLinksNothingAndGrantsNothing(ApiServer server) {
		ApiClient client = server.client();
		FakeGitHub.Account ada = server.newAccount("ada");
		server.signIn(client, ada.login());
		String adaId = Workspaces.userId(client);
		client.post("/auth/logout");
		server.signIn(client, account(server, "mallory", ada.email(), false).login());
		JsonNode me = client.get("/auth/me").json();
		assertThat(me.path("user").path("id").asString()).isNotEqualTo(adaId);
		assertThat(me.path("user").path("email").isNull()).isTrue();
		client.post("/auth/logout");
		server.signIn(client, account(server, "fakeadmin", Profile.ADMIN_EMAIL, false).login());
		assertThat(strings(client.get("/auth/me").json().path("workspaces"), "id")).doesNotContain("default");
	}

	/**
	 * On a server that outlives one test, an earlier test may have signed the admin in
	 * first: the new GitHub account is then linked to that admin by the verified email,
	 * who owns default all the same.
	 */
	@Test
	void theFirstSignInOfAnAdminEmailOwnsDefault(ApiServer server) {
		ApiClient client = server.client();
		server.signIn(client, account(server, "admin", Profile.ADMIN_EMAIL, true).login());
		JsonNode workspaces = client.get("/auth/me").json().path("workspaces");
		assertThat(Json.find(workspaces, "id", "default").path("role").asString()).isEqualTo("owner");
		assertThat(client.get(query("/projects", "workspace", "default")).status()).isEqualTo(200);
	}

	@Test
	void aWrongOrMissingStateSignsNobodyIn(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get("/auth/github/login");
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		String code = param(back, "code");
		String state = param(back, "state");
		ApiResponse res = client.get(query("/auth/github/callback", "code", code, "state", "not-the-state"));
		assertThat(res.location()).isEqualTo("/signin?error=state");
		client.cookies().clear(); // no state cookie at all (another browser, or it
									// expired)
		res = client.get(query("/auth/github/callback", "code", code, "state", state));
		assertThat(res.location()).isEqualTo("/signin?error=state");
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
		assertThat(res.setCookies()).noneMatch(c -> c.startsWith(SESSION + "="));
	}

	@Test
	void aTamperedStateCookieIsRefused(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get("/auth/github/login");
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		String[] parts = cookie(client, STATE).split("\\.", -1);
		assertThat(parts).hasSize(2);
		client.cookies().put(STATE, parts[0] + "x." + parts[1]);
		assertThat(client.open(back).location()).isEqualTo("/signin?error=state");
	}

	@Test
	void providerRefusalsEndOnTheSignInPage(ApiServer server) {
		ApiClient client = server.client();
		ApiResponse res = client.get(query("/auth/github/callback", "error", "access_denied"));
		assertThat(res.location()).isEqualTo("/signin?error=denied");
		String ada = server.newAccount("ada").login();
		server.github().refuseTokensFor(ada);
		assertThat(server.signIn(client, ada)).isEqualTo("/signin?error=provider");
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
	}

	@ParameterizedTest
	@ValueSource(strings = { "//evil.example/x", "https://evil.example/", "/\\evil.example", "projects" })
	void signInNeverLandsOnAnotherSite(String nextPath, ApiServer server) {
		ApiClient client = server.client();
		ApiResponse start = client.get(query("/auth/github/login", "next", nextPath));
		String back = server.github().authorize(start.location(), server.newAccount("ada").login());
		assertThat(client.open(back).location()).isEqualTo("/");
	}

	@Test
	void logoutEndsTheSessionEverywhereAtOnce(ApiServer server) {
		ApiClient client = server.signedIn("ada");
		String cookie = cookie(client, SESSION);
		ApiResponse res = client.post("/auth/logout");
		assertThat(res.status()).isEqualTo(204);
		assertThat(res.setCookies()).anyMatch(c -> c.startsWith(SESSION + "=\"\""));
		// the old cookie is dead even if a browser kept it
		client.cookies().put(SESSION, cookie);
		assertThat(client.get("/auth/me").status()).isEqualTo(401);
	}

	@Test
	void logoutEverywhereEndsEverySession(ApiServer server) {
		ApiClient client = server.client();
		String ada = server.newAccount("ada").login();
		server.signIn(client, ada);
		ApiClient other = server.client();
		server.signIn(other, ada);
		assertThat(other.get("/auth/me").status()).isEqualTo(200);
		client.post(query("/auth/logout", "everywhere", true));
		assertThat(other.get("/auth/me").status()).isEqualTo(401);
	}

	@Test
	void expiredSessionsAndDisabledAccountsAreSignedOut(ApiServer server) {
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
	void sessionsRecordTheClientIpOnlyFromATrustedProxy(ApiServer server) throws IOException, InterruptedException {
		ApiClient trusted = server.client();
		server.signIn(trusted, server.newAccount("ada").login());
		assertThat(sessionIp(server, cookie(trusted, SESSION))).isEqualTo(trusted.header("X-ArchSim-Client-IP"));

		String untrusted = signInFrom(UNTRUSTED, "203.0.113.8", server, server.newAccount("bob"));
		assertThat(sessionIp(server, untrusted)).isEqualTo(UNTRUSTED);
	}

	@Test
	void withoutSignInThereIsNothingToSignInTo(@On(Profile.LOCAL) ApiClient c) {
		assertThat(c.get("/auth/github/login").status()).isEqualTo(404);
		assertThat(c.post("/auth/logout").status()).isEqualTo(204);
	}

	// -- helpers
	// ----------------------------------------------------------------------------------

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
		String value = client.cookie(name);
		if (value == null) {
			throw new AssertionError("the client holds no " + name + ": " + client.cookies().keySet());
		}
		return value;
	}

	/**
	 * The {@code Set-Cookie} header that sets {@code name}; fails the test when there is
	 * none.
	 */
	private static String setCookie(ApiResponse res, String name) {
		String cookie = res.setCookie(name);
		if (cookie == null) {
			throw new AssertionError(res + " sets no " + name + ": " + res.setCookies());
		}
		return cookie;
	}

	/** The query parameters of a URL, first value of each. */
	private static Map<String, String> params(String url) {
		Map<String, String> out = new LinkedHashMap<>();
		String q = URI.create(url).getRawQuery();
		if (q != null) {
			for (String pair : q.split("&", -1)) {
				String[] kv = pair.split("=", 2);
				out.putIfAbsent(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
			}
		}
		return out;
	}

	private static String param(String url, String name) {
		String value = params(url).get(name);
		if (value == null) {
			throw new AssertionError(url + " has no " + name);
		}
		return value;
	}

	private static String decode(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
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
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The browser round trip as {@code account}, from the local address {@code from},
	 * sending {@code X-ArchSim-Client-IP: claimed}. Returns the session cookie. Skips the
	 * test where the machine cannot connect from that address.
	 */
	private static String signInFrom(String from, String claimed, ApiServer server, FakeGitHub.Account account)
			throws IOException, InterruptedException {
		InetAddress local = InetAddress.getByName(from);
		try (Socket probe = new Socket()) {
			probe.bind(new InetSocketAddress(local, 0));
		}
		catch (BindException e) {
			Assumptions.abort("this machine cannot connect from " + from + ": " + e.getMessage());
		}
		try (HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.localAddress(local)
			.build()) {
			HttpResponse<String> start = http
				.send(HttpRequest.newBuilder(server.url().resolve(ApiClient.API + "/auth/github/login"))
					.header("X-ArchSim-Client-IP", claimed)
					.build(), BodyHandlers.ofString());
			assertThat(start.statusCode()).as(start.body()).isEqualTo(302);
			String back = server.github()
				.authorize(start.headers().firstValue("location").orElseThrow(), account.login());
			assertThat(back).startsWith(Profile.HOSTED_PUBLIC_URL + "/");
			HttpResponse<String> done = http
				.send(HttpRequest.newBuilder(server.url().resolve(back.substring(Profile.HOSTED_PUBLIC_URL.length())))
					.header("X-ArchSim-Client-IP", claimed)
					.header("Cookie", STATE + "=" + cookieValue(start, STATE))
					.build(), BodyHandlers.ofString());
			assertThat(done.statusCode()).as(done.body()).isEqualTo(302);
			assertThat(done.headers().firstValue("location")).hasValue("/");
			return cookieValue(done, SESSION);
		}
	}

	private static String cookieValue(HttpResponse<?> res, String name) {
		return res.headers()
			.allValues("set-cookie")
			.stream()
			.filter(c -> c.startsWith(name + "="))
			.map(c -> c.split(";", 2)[0].substring(name.length() + 1))
			.findFirst()
			.orElseThrow(() -> new AssertionError(res.uri() + " sets no " + name));
	}

}
