package io.archsim.app.signin;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in paths of a provider that is not set up here (the hosted tests set up GitHub
 * only), which Spring Security's OAuth2 login leaves to {@link SignInController}: what is
 * too long is refused {@code 422} as FastAPI refused it, then the provider {@code 404}.
 */
class ProviderNotSetUpTests extends HostedTestSupport {

	private static final String AUTH = "/api/v1/auth";

	@Test
	void login_providerNotSetUp_isNotFound() {
		assertThat(this.mvc().get().uri(AUTH + "/google/login")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign-in with 'google' is not set up here");
	}

	@Test
	void callback_providerNotSetUpWithStateOverTwoHundredCharacters_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri(AUTH + "/google/callback?state=" + "s".repeat(201))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("query", "state"));
	}

	@Test
	void callback_providerNotSetUpWithCodeOverTwoThousandCharacters_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri(AUTH + "/google/callback?code=" + "c".repeat(2001))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("query", "code"));
	}

	@Test
	void callback_providerNotSetUpWithErrorOverTwoHundredCharacters_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri(AUTH + "/google/callback?error=" + "e".repeat(201))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("query", "error"));
	}

	@Test
	void callback_providerNotSetUpWithLongStateBeforeAComma_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri(AUTH + "/google/callback?state=" + "s".repeat(201) + ",x")).hasStatus(422);
	}

	@Test
	void callback_providerNotSetUp_isNotFound() {
		assertThat(this.mvc().get().uri(AUTH + "/google/callback?code=c&state=s")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign-in with 'google' is not set up here");
	}

}
