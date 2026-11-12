package io.archsim.app.signin;

import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * The providers' access tokens, which Spring Security's OAuth2 login keeps for each
 * person signed in. One is needed only to read the account, and nothing calls a provider
 * for anyone afterwards, so it is dropped before the person is signed in, as the
 * hand-written client dropped it. Spring Security's repositories are final, and the
 * interface's generic {@code loadAuthorizedClient} cannot be implemented without a
 * finding, so the login keeps the token in Spring's in-memory one until then.
 */
final class ProviderTokens {

	private final OAuth2AuthorizedClientRepository kept;

	/**
	 * Where the providers' tokens are kept until the sign-in finishes.
	 * @param registrations the providers
	 */
	ProviderTokens(ClientRegistrationRepository registrations) {
		this.kept = new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(
				new InMemoryOAuth2AuthorizedClientService(registrations));
	}

	/**
	 * Where the OAuth2 login keeps a provider's token.
	 * @return the repository
	 */
	OAuth2AuthorizedClientRepository repository() {
		return this.kept;
	}

	/**
	 * Signs a person in once the provider's token is dropped.
	 * @param signIn what signs the person in
	 * @return the OAuth2 login's success handler
	 */
	AuthenticationSuccessHandler droppedBefore(AuthenticationSuccessHandler signIn) {
		return (request, response, authentication) -> {
			if (authentication instanceof OAuth2AuthenticationToken signedIn) {
				this.kept.removeAuthorizedClient(signedIn.getAuthorizedClientRegistrationId(), signedIn, request,
						response);
			}
			signIn.onAuthenticationSuccess(request, response, authentication);
		};
	}

}
