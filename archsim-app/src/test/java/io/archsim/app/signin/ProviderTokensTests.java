package io.archsim.app.signin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider's access token, which Spring Security's OAuth2 login keeps for each person
 * signed in, is dropped before the person is signed in.
 */
class ProviderTokensTests {

	private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

	private static final OAuthProviders PROVIDERS = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test"),
			TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop", "github-client-id=id",
					"github-client-secret=secret"));

	private static final OAuth2AuthenticationToken ADA = new OAuth2AuthenticationToken(
			new DefaultOAuth2User(List.of(), Map.of("id", 42), "id"), List.of(), "github");

	private final ProviderTokens tokens = new ProviderTokens(PROVIDERS.registrations());

	private final MockHttpServletRequest request = new MockHttpServletRequest();

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@Test
	void droppedBefore_signIn_dropsTheProvidersTokenFirst() throws Exception {
		this.tokens.repository().saveAuthorizedClient(gitHubToken(), ADA, this.request, this.response);
		AtomicReference<@Nullable OAuth2AuthorizedClient> keptWhileSigningIn = new AtomicReference<>(gitHubToken());

		this.tokens
			.droppedBefore((request, response, authentication) -> keptWhileSigningIn
				.set(this.tokens.repository().loadAuthorizedClient("github", authentication, request)))
			.onAuthenticationSuccess(this.request, this.response, ADA);

		assertThat(keptWhileSigningIn.get()).isNull();
	}

	@Test
	void droppedBefore_signIn_signsThePersonIn() throws Exception {
		AtomicReference<Authentication> signedIn = new AtomicReference<>();

		this.tokens.droppedBefore((request, response, authentication) -> signedIn.set(authentication))
			.onAuthenticationSuccess(this.request, this.response, ADA);

		assertThat(signedIn.get()).isSameAs(ADA);
	}

	private static OAuth2AuthorizedClient gitHubToken() {
		return new OAuth2AuthorizedClient(PROVIDERS.find("github").orElseThrow().registration(), ADA.getName(),
				new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", NOW, NOW.plusSeconds(60)));
	}

}
