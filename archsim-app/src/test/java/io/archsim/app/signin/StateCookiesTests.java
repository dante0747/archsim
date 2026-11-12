package io.archsim.app.signin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import jakarta.servlet.http.Cookie;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.SigningKeys;
import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state cookie that carries a sign-in from the provider's page back to the callback,
 * where Spring Security's OAuth2 login keeps its authorization requests: signed, so
 * nobody can forge or change it, and good for ten minutes.
 */
class StateCookiesTests {

	/**
	 * What the Python API signs for
	 * {@code {"p":"github","s":"st","v":"ver","n":"/projects/café"}}.
	 */
	private static final String PYTHONS = "eyJwIjoiZ2l0aHViIiwicyI6InN0IiwidiI6InZlciIsIm4iOiIvcHJvamVjdHMv"
			+ "Y2FmXHUwMGU5IiwiZXhwIjo0MTAyNDQ0ODAwLjI1fQ.0gyF-8kRlcthgTRdp2eIuF6lXuFybnezGUUGjZhGDaU";

	private static final String SECRET_KEY = "secret-key=test-secret-key-0123456789-abcdefghijklmnop";

	private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

	private static final OAuthProviders PROVIDERS = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test"),
			TestProperties.signIn(SECRET_KEY, "github-client-id=gh-id", "github-client-secret=gh-secret",
					"google-client-id=g-id", "google-client-secret=g-secret"));

	private final StateCookies cookies = cookies(NOW);

	@Test
	void verify_whatItSigned_isTheState() {
		SignInState state = new SignInState("github", "st", "ver", "/projects", "", NOW.getEpochSecond() + 600.0);

		assertThat(this.cookies.verify(this.cookies.sign(state))).contains(state);
	}

	@Test
	void verify_whatThePythonApiSigned_isItsState() {
		assertThat(this.cookies.verify(PYTHONS)).map(SignInState::next).contains("/projects/café");
	}

	@Test
	void verify_changedBody_isNothing() {
		String[] parts = signedCookie(github()).split("\\.", -1);

		assertThat(this.cookies.verify(parts[0] + "x." + parts[1])).isEmpty();
	}

	@Test
	void verify_afterTenMinutes_isNothing() {
		String signed = signedCookie(github());

		assertThat(cookies(NOW.plusSeconds(601)).verify(signed)).isEmpty();
	}

	@Test
	void verify_noCookie_isNothing() {
		assertThat(this.cookies.verify(null)).isEmpty();
	}

	@Test
	void verify_notBase64_isNothing() {
		assertThat(this.cookies.verify("!!!.???")).isEmpty();
	}

	@Test
	void saveAuthorizationRequest_signIn_setsTheStateCookieForTenMinutes() {
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.cookies.saveAuthorizationRequest(github(), new MockHttpServletRequest(), response);

		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith(BrowserCookie.STATE_NAME + "=")
			.contains("HttpOnly", "Max-Age=600", "Path=/", "SameSite=lax", "Secure");
	}

	@Test
	void loadAuthorizationRequest_callbackWithItsState_isTheSignIn() {
		OAuth2AuthorizationRequest loaded = this.cookies.loadAuthorizationRequest(callback(github(), "st"));

		assertThat(loaded).isNotNull()
			.returns("st", OAuth2AuthorizationRequest::getState)
			.returns("https://archsim.test/api/v1/auth/github/callback", OAuth2AuthorizationRequest::getRedirectUri)
			.extracting(OAuth2AuthorizationRequest::getAttributes, InstanceOfAssertFactories.MAP)
			.containsEntry(OAuth2ParameterNames.REGISTRATION_ID, "github")
			.containsEntry(PkceParameterNames.CODE_VERIFIER, "ver")
			.containsEntry(StateCookies.NEXT, "/projects");
	}

	@Test
	void loadAuthorizationRequest_openIdConnect_keepsTheNonce() {
		OAuth2AuthorizationRequest google = OAuth2AuthorizationRequest.from(request("google"))
			.attributes((attributes) -> attributes.put(OidcParameterNames.NONCE, "nonce"))
			.build();

		OAuth2AuthorizationRequest loaded = this.cookies.loadAuthorizationRequest(callback(google, "st"));

		assertThat(loaded).isNotNull()
			.satisfies((signIn) -> assertThat(signIn.getScopes()).contains("openid"))
			.extracting(OAuth2AuthorizationRequest::getAttributes, InstanceOfAssertFactories.MAP)
			.containsEntry(OidcParameterNames.NONCE, "nonce");
	}

	@Test
	void loadAuthorizationRequest_callbackWithAnotherState_isNothing() {
		assertThat(this.cookies.loadAuthorizationRequest(callback(github(), "not-the-state"))).isNull();
	}

	@Test
	void loadAuthorizationRequest_callbackOfAnotherProvider_isNothing() {
		MockHttpServletRequest request = callback(github(), "st");
		request.setRequestURI("/api/v1/auth/google/callback");

		assertThat(this.cookies.loadAuthorizationRequest(request)).isNull();
	}

	@Test
	void loadAuthorizationRequest_withoutTheCookie_isNothing() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setParameter(OAuth2ParameterNames.STATE, "st");

		assertThat(this.cookies.loadAuthorizationRequest(request)).isNull();
	}

	@Test
	void removeAuthorizationRequest_callback_clearsTheCookieOnce() {
		MockHttpServletRequest request = callback(github(), "st");
		MockHttpServletResponse response = new MockHttpServletResponse();

		OAuth2AuthorizationRequest removed = this.cookies.removeAuthorizationRequest(request, response);
		this.cookies.clear(response);

		assertThat(removed).isNotNull();
		assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).singleElement()
			.asString()
			.startsWith(BrowserCookie.STATE_NAME + "=\"\";");
	}

	@Test
	void next_callback_isWhereSignInStarted() {
		assertThat(this.cookies.next(callback(github(), "st"))).isEqualTo("/projects");
	}

	@Test
	void next_withoutTheCookie_isHome() {
		assertThat(this.cookies.next(new MockHttpServletRequest())).isEqualTo(Landing.HOME);
	}

	private static OAuth2AuthorizationRequest github() {
		return request("github");
	}

	private static OAuth2AuthorizationRequest request(String provider) {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://provider.example/authorize")
			.clientId("id")
			.redirectUri("https://archsim.test/api/v1/auth/" + provider + "/callback")
			.state("st")
			.attributes((attributes) -> {
				attributes.put(OAuth2ParameterNames.REGISTRATION_ID, provider);
				attributes.put(PkceParameterNames.CODE_VERIFIER, "ver");
				attributes.put(StateCookies.NEXT, "/projects");
			})
			.build();
	}

	/** The cookie's value that saving a sign-in sets. */
	private String signedCookie(OAuth2AuthorizationRequest signIn) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.cookies.saveAuthorizationRequest(signIn, new MockHttpServletRequest(), response);
		String header = String.valueOf(response.getHeader(HttpHeaders.SET_COOKIE));
		return header.substring(BrowserCookie.STATE_NAME.length() + 1).split(";", 2)[0];
	}

	/** The provider's return to the callback, with the state it sends and the cookie. */
	private MockHttpServletRequest callback(OAuth2AuthorizationRequest signIn, String state) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET",
				"/api/v1/auth/" + signIn.getAttribute(OAuth2ParameterNames.REGISTRATION_ID) + "/callback");
		request.setCookies(new Cookie(BrowserCookie.STATE_NAME, signedCookie(signIn)));
		request.setParameter(OAuth2ParameterNames.STATE, state);
		request.setParameter(OAuth2ParameterNames.CODE, "code");
		return request;
	}

	private static StateCookies cookies(Instant now) {
		SigningKeys keys = new SigningKeys(TestProperties.signIn(SECRET_KEY));
		return new StateCookies(keys, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC), PROVIDERS);
	}

}
