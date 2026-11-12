package io.archsim.app.signin;

import java.time.Clock;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.Visitors;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.TestProperties;
import io.archsim.app.limits.RateLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.mock;

/**
 * The sign-in {@code GET /auth/<provider>/login} starts: Spring Security's, with what
 * each provider is told besides and where to land afterwards.
 */
class SignInStartsTests {

	private static final ArchsimProperties SETTINGS = TestProperties.with("auth-mode=oauth",
			"public-url=https://archsim.test");

	private static final OAuthProviders PROVIDERS = new OAuthProviders(SETTINGS,
			TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop", "github-client-id=gh-id",
					"github-client-secret=gh-secret", "google-client-id=g-id", "google-client-secret=g-secret"));

	private final SignInStarts starts = new SignInStarts(PROVIDERS, mock(RateLimits.class),
			new Visitors(SETTINGS, Clock.systemUTC()), JsonMapper.builder().build());

	@Test
	void resolve_github_letsPeopleSignUp() {
		assertThat(this.starts.resolve(login("GET", "github"))).isNotNull()
			.extracting(OAuth2AuthorizationRequest::getAuthorizationRequestUri, InstanceOfAssertFactories.STRING)
			.contains("allow_signup=true", "code_challenge_method=S256");
	}

	@Test
	void resolve_google_asksWhichAccountAndSendsANonce() {
		assertThat(this.starts.resolve(login("GET", "google"))).isNotNull()
			.extracting(OAuth2AuthorizationRequest::getAuthorizationRequestUri, InstanceOfAssertFactories.STRING)
			.contains("prompt=select_account", "access_type=online", "nonce=", "code_challenge_method=S256");
	}

	@Test
	void resolve_google_keepsTheNonceToCheckTheIdToken() {
		assertThat(this.starts.resolve(login("GET", "google"))).isNotNull()
			.extracting(OAuth2AuthorizationRequest::getAttributes, InstanceOfAssertFactories.MAP)
			.containsKey(OidcParameterNames.NONCE);
	}

	@Test
	void resolve_nextOnAnotherSite_landsOnThisSite() {
		MockHttpServletRequest request = login("GET", "github");
		request.setParameter(StateCookies.NEXT, "//evil.example/x");

		assertThat(this.starts.resolve(request)).isNotNull()
			.extracting(OAuth2AuthorizationRequest::getAttributes, InstanceOfAssertFactories.MAP)
			.containsEntry(StateCookies.NEXT, Landing.HOME);
	}

	@Test
	void resolve_head_startsASignInAsGetDoes() {
		assertThat(this.starts.resolve(login("HEAD", "github"))).isNotNull();
	}

	@Test
	void resolve_providerNotSetUp_isNothing() {
		assertThat(this.starts.resolve(login("GET", "gitlab"))).isNull();
	}

	private static MockHttpServletRequest login(String method, String provider) {
		return new MockHttpServletRequest(method, "/api/v1/auth/" + provider + "/login");
	}

}
