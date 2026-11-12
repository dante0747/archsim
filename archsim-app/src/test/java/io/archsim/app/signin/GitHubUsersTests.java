package io.archsim.app.signin;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Who signed in with GitHub: its {@code /user}, and the email GitHub's rule picks from
 * {@code /user/emails}, both read with the access token.
 */
class GitHubUsersTests {

	private static final String API = "http://github.test/api/v3";

	private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

	private static final OAuthProviders PROVIDERS = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test"),
			TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop", "github-client-id=id",
					"github-client-secret=secret", "github-url=http://github.test", "github-api-url=" + API));

	private static final String ADA = """
			{"id": 42, "login": "ada", "name": "Ada Lovelace", "avatar_url": "https://avatars.example/42"}""";

	private final RestTemplate rest = OAuthClients.userInfoClient(new SimpleClientHttpRequestFactory());

	private final MockRestServiceServer github = MockRestServiceServer.bindTo(this.rest).build();

	private final GitHubUsers users = new GitHubUsers(this.rest, JsonMapper.builder().build());

	@Test
	void loadUser_primaryVerifiedEmail_isTheAccountsEmail() {
		this.github.expect(requestTo(API + "/user"))
			.andExpect(header("Authorization", "Bearer token"))
			.andRespond(withSuccess(ADA, MediaType.APPLICATION_JSON));
		this.github.expect(requestTo(API + "/user/emails"))
			.andExpect(header("Authorization", "Bearer token"))
			.andRespond(withSuccess("""
					[{"email": "old@example.org", "primary": false, "verified": true},
					 {"email": "Ada@Example.org", "primary": true, "verified": true}]""", MediaType.APPLICATION_JSON));

		ProviderProfile profile = ((ProviderAccount) this.users.loadUser(request())).profile();

		assertThat(profile).isEqualTo(new ProviderProfile("42", "Ada Lovelace", "ada@example.org", true,
				"https://avatars.example/42", "ada"));
	}

	@Test
	void loadUser_emailsRefused_hasNoEmail() {
		this.github.expect(requestTo(API + "/user")).andRespond(withSuccess(ADA, MediaType.APPLICATION_JSON));
		this.github.expect(requestTo(API + "/user/emails")).andRespond(withStatus(HttpStatus.FORBIDDEN));

		ProviderProfile profile = ((ProviderAccount) this.users.loadUser(request())).profile();

		assertThat(profile.email()).isNull();
	}

	@Test
	void loadUser_emailsUnreachable_failsAsTheProvider() {
		this.github.expect(requestTo(API + "/user")).andRespond(withSuccess(ADA, MediaType.APPLICATION_JSON));
		this.github.expect(requestTo(API + "/user/emails")).andRespond(withException(new IOException("timed out")));

		SignInFailure failure = catchThrowableOfType(SignInFailure.class, () -> this.users.loadUser(request()));

		assertThat(failure.code()).isEqualTo("provider");
	}

	@Test
	void loadUser_emptyAnswer_failsAsTheProvider() {
		this.github.expect(requestTo(API + "/user")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		SignInFailure failure = catchThrowableOfType(SignInFailure.class, () -> this.users.loadUser(request()));

		assertThat(failure.code()).isEqualTo("provider");
	}

	@Test
	void loadUser_noAccountId_failsAsTheProvider() {
		this.github.expect(requestTo(API + "/user"))
			.andRespond(withSuccess("{\"id\": null, \"login\": \"ada\"}", MediaType.APPLICATION_JSON));

		SignInFailure failure = catchThrowableOfType(SignInFailure.class, () -> this.users.loadUser(request()));

		assertThat(failure.code()).isEqualTo("provider");
	}

	private static OAuth2UserRequest request() {
		return new OAuth2UserRequest(PROVIDERS.find("github").orElseThrow().registration(),
				new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", NOW, NOW.plusSeconds(60)));
	}

}
