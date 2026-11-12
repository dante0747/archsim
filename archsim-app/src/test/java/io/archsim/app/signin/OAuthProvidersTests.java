package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The providers people sign in with, as Spring Security's OAuth2 login registers them.
 */
class OAuthProvidersTests {

	private static final String SECRET_KEY = "secret-key=test-secret-key-0123456789-abcdefghijklmnop";

	private final OAuthProviders providers = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test/"),
			TestProperties.signIn(SECRET_KEY, "github-client-id=gh-id", "github-client-secret=gh-secret",
					"github-url=http://127.0.0.1:9/", "github-api-url=http://127.0.0.1:9/api/v3",
					"google-client-id=g-id", "google-client-secret=g-secret"));

	@Test
	void registration_github_postsItsSecretAndProvesItsKey() {
		ClientRegistration github = registration("github");

		assertThat(github.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
		assertThat(github.getClientSettings().isRequireProofKey()).isTrue();
	}

	@Test
	void registration_github_comesBackToThePublicAddress() {
		assertThat(registration("github").getRedirectUri())
			.isEqualTo("https://archsim.test/api/v1/auth/github/callback");
	}

	@Test
	void registration_github_asksToReadTheAccountAndItsEmails() {
		assertThat(registration("github").getScopes()).containsExactly("read:user", "user:email");
	}

	@Test
	void registration_github_usesTheSiteAndApiItIsGiven() {
		ClientRegistration.ProviderDetails github = registration("github").getProviderDetails();

		assertThat(github.getAuthorizationUri()).isEqualTo("http://127.0.0.1:9/login/oauth/authorize");
		assertThat(github.getTokenUri()).isEqualTo("http://127.0.0.1:9/login/oauth/access_token");
		assertThat(github.getUserInfoEndpoint().getUri()).isEqualTo("http://127.0.0.1:9/api/v3/user");
	}

	@Test
	void registration_google_isOpenIdConnectWithItsSecretPostedAndItsKeyProved() {
		ClientRegistration google = registration("google");

		assertThat(google.getScopes()).containsExactly("openid", "email", "profile");
		assertThat(google.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
		assertThat(google.getClientSettings().isRequireProofKey()).isTrue();
		assertThat(google.getRedirectUri()).isEqualTo("https://archsim.test/api/v1/auth/google/callback");
	}

	@Test
	void signInParameters_github_letPeopleSignUp() {
		assertThat(this.providers.find("github").orElseThrow().signInParameters()).containsEntry("allow_signup",
				"true");
	}

	@Test
	void signInParameters_google_askWhichAccount() {
		assertThat(this.providers.find("google").orElseThrow().signInParameters())
			.containsEntry("prompt", "select_account")
			.containsEntry("access_type", "online");
	}

	@Test
	void find_providerNotSetUp_isNothing() {
		assertThat(this.providers.find("gitlab")).isEmpty();
	}

	@Test
	void callbacks_twoProvidersSetUp_matchOnlyTheirs() {
		assertThat(this.providers.callbacks()).isEqualTo("/api/v1/auth/{registrationId:github|google}/callback");
	}

	@Test
	void registrations_providerNotSetUp_isNothing() {
		assertThat(this.providers.registrations().findByRegistrationId("gitlab")).isNull();
	}

	private ClientRegistration registration(String name) {
		return this.providers.find(name).orElseThrow().registration();
	}

}
