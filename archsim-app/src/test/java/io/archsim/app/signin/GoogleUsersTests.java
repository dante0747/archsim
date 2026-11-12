package io.archsim.app.signin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Who signed in with Google, read from the claims of the ID token Spring Security's
 * OpenID Connect login has checked.
 */
class GoogleUsersTests {

	private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

	private static final OAuthProviders PROVIDERS = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test"),
			TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop", "google-client-id=g-id",
					"google-client-secret=g-secret"));

	@Test
	void loadUser_verifiedEmail_isTrusted() {
		ProviderProfile profile = profileOf(Map.of("sub", "1234", "email", "Ada@Example.org", "email_verified", true,
				"name", "Ada Lovelace", "picture", "https://pictures.example/ada"));

		assertThat(profile).isEqualTo(new ProviderProfile("1234", "Ada Lovelace", "ada@example.org", true,
				"https://pictures.example/ada", null));
	}

	@Test
	void loadUser_unverifiedEmail_isNotTrusted() {
		ProviderProfile profile = profileOf(
				Map.of("sub", "1234", "email", "ada@example.org", "email_verified", false, "name", "Ada"));

		assertThat(profile.emailVerified()).isFalse();
	}

	@Test
	void loadUser_noName_isNamedByItsEmail() {
		ProviderProfile profile = profileOf(Map.of("sub", "1234", "email", "ada@example.org"));

		assertThat(profile.name()).isEqualTo("ada@example.org");
	}

	@Test
	void loadUser_emptyAnswer_failsAsTheProvider() {
		GoogleUsers users = new GoogleUsers((checked) -> {
			throw new IllegalArgumentException("userInfo response body cannot be null");
		}, JsonMapper.builder().build());

		SignInFailure failure = catchThrowableOfType(SignInFailure.class,
				() -> users.loadUser(request(Map.of("sub", "1234"))));

		assertThat(failure.code()).isEqualTo("provider");
	}

	private static ProviderProfile profileOf(Map<String, Object> claims) {
		GoogleUsers users = new GoogleUsers((checked) -> new DefaultOidcUser(List.of(), checked.getIdToken()),
				JsonMapper.builder().build());

		return ((ProviderAccount) users.loadUser(request(claims))).profile();
	}

	private static OidcUserRequest request(Map<String, Object> claims) {
		OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
			.issuedAt(NOW)
			.expiresAt(NOW.plusSeconds(60))
			.claims((all) -> all.putAll(claims))
			.build();
		return new OidcUserRequest(PROVIDERS.find("google").orElseThrow().registration(),
				new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", NOW, NOW.plusSeconds(60)), idToken);
	}

}
