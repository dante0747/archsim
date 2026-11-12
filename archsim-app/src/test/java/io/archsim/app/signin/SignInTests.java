package io.archsim.app.signin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.apitests.FakeGitHub;
import io.archsim.app.access.BrowserCsrf;
import io.archsim.app.access.Principal;
import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in with GitHub, against the black-box suite's stand-in: PKCE and the state
 * check, accounts and their workspaces, administrators, and signing out.
 */
class SignInTests extends HostedTestSupport {

	private static final String AUTH = "/api/v1/auth";

	private static final String STATE = "__Host-archsim_oauth";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private StateCookies states;

	@Test
	void config_hosted_isOAuthWithGitHub() {
		assertThat(this.mvc().get().uri(AUTH + "/config")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"mode\": \"oauth\", \"providers\": [\"github\"]}");
	}

	@Test
	void login_github_redirectsToTheProviderWithPkce() {
		MvcTestResult started = this.mvc().get().uri(AUTH + "/github/login?next=/projects").exchange();

		assertThat(started).hasStatus(302);
		assertThat(location(started)).startsWith(github().url() + "/login/oauth/authorize?response_type=code&")
			.contains("code_challenge_method=S256",
					"redirect_uri=https%3A%2F%2Farchsim.test%2Fapi%2Fv1%2Fauth%2Fgithub%2Fcallback");
		assertThat(started.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith(STATE + "=")
			.contains("HttpOnly", "Max-Age=600", "Path=/", "SameSite=lax", "Secure");
	}

	@Test
	void login_providerNotSetUp_isNotFound() {
		assertThat(this.mvc().get().uri(AUTH + "/google/login")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign-in with 'google' is not set up here");
	}

	@Test
	void login_nextOverFiveHundredCharacters_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri(AUTH + "/github/login?next=/" + "a".repeat(500))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("query", "next"));
	}

	@Test
	void callback_firstSignIn_landsWhereItStartedWithASession() {
		FakeGitHub.Account ada = account("Ada Lovelace", null, false);

		MvcTestResult done = signIn(ada, "/projects/abc");

		assertThat(done).hasStatus(302).hasHeader(HttpHeaders.LOCATION, "/projects/abc");
		assertThat(done.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(3)
			.first()
			.asString()
			.startsWith(STATE + "=\"\";");
	}

	@Test
	void callback_firstSignIn_makesThePersonAndTheirPersonalWorkspace() {
		String email = unique("ada") + "@example.org";
		Browser browser = signedIn(account("Ada Lovelace", email, true));

		assertThat(browser.me()).bodyJson().isLenientlyEqualTo("""
				{"mode": "oauth", "kind": "session", "user": {"name": "Ada Lovelace", "email": "%s"},
				 "workspaces": [{"name": "Ada's workspace", "kind": "personal", "role": "owner",
				                 "maxConcurrentRuns": 2, "maxRunsPerHour": 60}]}""".formatted(email));
	}

	@Test
	void callback_firstSignIn_givesTheCsrfTokenInACookieAndInMe() {
		Browser browser = signedIn(account("Ada", null, false));

		assertThat(browser.me()).bodyJson().extractingPath("$.csrfToken").isEqualTo(browser.csrf());
	}

	@Test
	void callback_again_findsTheSamePerson() {
		FakeGitHub.Account ada = account("Ada", null, false);
		String first = userId(signedIn(ada));

		assertThat(userId(signedIn(ada))).isEqualTo(first);
	}

	@Test
	void callback_newAccountWithTheSameVerifiedEmail_isTheSamePerson() {
		String email = unique("ada") + "@example.org";
		String first = userId(signedIn(account("Ada", email, true)));

		assertThat(userId(signedIn(account("Ada again", email, true)))).isEqualTo(first);
	}

	@Test
	void callback_unverifiedEmailOfSomeoneElse_isSomeoneNew() {
		String email = unique("ada") + "@example.org";
		String ada = userId(signedIn(account("Ada", email, true)));

		assertThat(userId(signedIn(account("Mallory", email, false)))).isNotEqualTo(ada);
	}

	@Test
	void callback_firstSignInWithAdminEmail_ownsTheDefaultWorkspace() {
		Browser admin = signedIn(account("Admin", ADMIN_EMAIL, true));

		assertThat(admin.me()).bodyJson()
			.extractingPath("$.workspaces[?(@.id == 'default')].role")
			.asArray()
			.containsExactly("owner");
	}

	@Test
	void callback_wrongState_landsOnTheSignInPage() {
		MvcTestResult started = this.mvc().get().uri(AUTH + "/github/login").exchange();
		String back = github().authorize(location(started), account("Ada", null, false).login());

		MvcTestResult done = this.mvc()
			.get()
			.uri(path(back).replaceFirst("state=[^&]+", "state=wrong"))
			.cookie(stateCookie(started))
			.exchange();

		assertThat(done).hasStatus(302).hasHeader(HttpHeaders.LOCATION, "/signin?error=state");
	}

	@Test
	void callback_emptyCode_landsOnTheSignInPage() {
		MvcTestResult started = this.mvc().get().uri(AUTH + "/github/login").exchange();
		String back = github().authorize(location(started), account("Ada", null, false).login());

		MvcTestResult done = this.mvc()
			.get()
			.uri(path(back).replaceFirst("code=[^&]+", "code="))
			.cookie(stateCookie(started))
			.exchange();

		assertThat(done).hasHeader(HttpHeaders.LOCATION, "/signin?error=state");
	}

	@Test
	void callback_stateInAHeader_isNotRead() {
		MvcTestResult done = this.mvc()
			.get()
			.uri(AUTH + "/github/callback")
			.header("State", "s".repeat(201))
			.header("Code", "c")
			.exchange();

		assertThat(done).hasStatus(302).hasHeader(HttpHeaders.LOCATION, "/signin?error=state");
	}

	@Test
	void callback_stateCookieNamingAnotherSite_landsOnThisSite() {
		MvcTestResult started = this.mvc().get().uri(AUTH + "/github/login").exchange();
		String back = github().authorize(location(started), account("Ada", null, false).login());
		SignInState kept = this.states.verify(cookie(started, STATE)).orElseThrow();
		String foreign = this.states
			.sign(new SignInState(kept.provider(), kept.state(), kept.verifier(), "//evil.example", kept.expiresAt()));

		MvcTestResult done = this.mvc().get().uri(path(back)).cookie(new Cookie(STATE, foreign)).exchange();

		assertThat(done).hasHeader(HttpHeaders.LOCATION, "/");
	}

	@Test
	void callback_providerRefusesTheCode_landsOnTheSignInPage() {
		FakeGitHub.Account ada = account("Ada", null, false);
		github().refuseTokensFor(ada.login());

		assertThat(signIn(ada, null)).hasHeader(HttpHeaders.LOCATION, "/signin?error=provider");
	}

	@Test
	void callback_personSaidNo_landsOnTheSignInPage() {
		assertThat(this.mvc().get().uri(AUTH + "/github/callback?error=access_denied")).hasHeader(HttpHeaders.LOCATION,
				"/signin?error=denied");
	}

	@Test
	void callback_disabledAccount_landsOnTheSignInPage() {
		FakeGitHub.Account ada = account("Ada", null, false);
		String id = userId(signedIn(ada));
		this.jdbc().sql("UPDATE users SET disabled_at = now() WHERE id = :id").param("id", id).update();

		assertThat(signIn(ada, null)).hasHeader(HttpHeaders.LOCATION, "/signin?error=disabled");
	}

	@Test
	void logout_session_endsItAtOnce() {
		Browser browser = signedIn(account("Ada", null, false));

		MvcTestResult out = browser.logout("");

		assertThat(out).hasStatus(204);
		assertThat(out.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.anyMatch((cookie) -> cookie.startsWith(Principal.SESSION_COOKIE + "=\"\";"));
		assertThat(browser.me()).hasStatus(401);
	}

	@Test
	void logout_everywhere_endsEverySessionOfThePerson() {
		FakeGitHub.Account ada = account("Ada", null, false);
		Browser laptop = signedIn(ada);
		Browser phone = signedIn(ada);

		laptop.logout("?everywhere=yes");

		assertThat(phone.me()).hasStatus(401);
	}

	@Test
	void logout_withoutCsrfHeader_isForbiddenAndEndsNothing() {
		Browser browser = signedIn(account("Ada", null, false));

		MvcTestResult out = this.mvc().post().uri(AUTH + "/logout").cookie(browser.session()).exchange();

		assertThat(out).hasStatus(403);
		assertThat(browser.me()).hasStatusOk();
	}

	@Test
	void logout_everywhereThatIsNoBoolean_isRefusedAsFastApiRefusesIt() {
		Browser browser = signedIn(account("Ada", null, false));

		assertThat(browser.logout("?everywhere=maybe")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].type")
			.isEqualTo("bool_parsing");
	}

	// -- helpers

	private FakeGitHub.Account account(String name, @Nullable String email, boolean verified) {
		return github().add(unique("login"), email, verified, name);
	}

	private MvcTestResult signIn(FakeGitHub.Account account, @Nullable String next) {
		String login = AUTH + "/github/login" + ((next != null) ? "?next=" + next : "");
		MvcTestResult started = this.mvc().get().uri(login).exchange();
		String back = github().authorize(location(started), account.login());
		return this.mvc().get().uri(path(back)).cookie(stateCookie(started)).exchange();
	}

	private Browser signedIn(FakeGitHub.Account account) {
		MvcTestResult done = signIn(account, null);
		return new Browser(cookie(done, Principal.SESSION_COOKIE), cookie(done, BrowserCsrf.COOKIE));
	}

	private static String userId(Browser browser) {
		return JSON.readTree(browser.me().getResponse().getContentAsByteArray()).path("user").path("id").asString();
	}

	private static String location(MvcTestResult result) {
		return String.valueOf(result.getResponse().getHeader(HttpHeaders.LOCATION));
	}

	private static String path(String url) {
		return url.substring(PUBLIC_URL.length());
	}

	private static Cookie stateCookie(MvcTestResult started) {
		return new Cookie(STATE, cookie(started, STATE));
	}

	private static String cookie(MvcTestResult result, String name) {
		Optional<String> header = result.getResponse()
			.getHeaders(HttpHeaders.SET_COOKIE)
			.stream()
			.filter((cookie) -> cookie.startsWith(name + "="))
			.findFirst();
		return header.orElseThrow(() -> new AssertionError("no " + name + " cookie"))
			.substring(name.length() + 1)
			.split(";", 2)[0];
	}

	private static String unique(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * A browser signed in: its session and CSRF cookies, both of which it sends.
	 */
	private final class Browser {

		private final String session;

		private final String csrf;

		Browser(String session, String csrf) {
			this.session = session;
			this.csrf = csrf;
		}

		Cookie session() {
			return new Cookie(Principal.SESSION_COOKIE, this.session);
		}

		String csrf() {
			return this.csrf;
		}

		Cookie csrfCookie() {
			return new Cookie(BrowserCsrf.COOKIE, this.csrf);
		}

		MvcTestResult me() {
			return mvc().get().uri(AUTH + "/me").cookie(session(), csrfCookie()).exchange();
		}

		MvcTestResult logout(String query) {
			return mvc().post()
				.uri(AUTH + "/logout" + query)
				.cookie(session(), csrfCookie())
				.header(BrowserCsrf.HEADER, this.csrf)
				.exchange();
		}

	}

}
