package io.archsim.app.signin;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Sign-in with GitHub and Google through Spring Security's OAuth2 login (ADR-0065), as
 * the security chain applies it with sign-in. {@link SignInStarts} starts a sign-in at
 * {@code /auth/<provider>/login}, which {@link StateCookies} keeps in the signed state
 * cookie; at {@code /auth/<provider>/callback} the login checks the state, exchanges the
 * code and reads the account with {@link OAuthClients}' beans, and {@link Callbacks}
 * starts the session, once {@link ProviderTokens} has dropped the provider's token, or
 * lands on the sign-in page. The web app's {@code /signin} is the login page, so Spring
 * Security makes none of its own; the paths of a provider that is not set up reach
 * {@link SignInController}. Not a {@code Customizer} bean, which Spring Security would
 * apply to every chain, without sign-in too.
 */
@Component
public final class OAuthSignIn {

	/** The web app's sign-in page. */
	private static final String SIGN_IN_PAGE = "/signin";

	private final OAuthProviders providers;

	private final StateCookies states;

	private final SignInStarts starts;

	private final Callbacks callbacks;

	OAuthSignIn(OAuthProviders providers, StateCookies states, SignInStarts starts, Callbacks callbacks) {
		this.providers = providers;
		this.states = states;
		this.starts = starts;
		this.callbacks = callbacks;
	}

	/**
	 * Sets up the OAuth2 login of a chain.
	 * @param login the chain's OAuth2 login
	 */
	public void configure(OAuth2LoginConfigurer<HttpSecurity> login) {
		ClientRegistrationRepository registrations = this.providers.registrations();
		ProviderTokens providerTokens = new ProviderTokens(registrations);
		login.clientRegistrationRepository(registrations)
			.authorizedClientRepository(providerTokens.repository())
			.loginPage(SIGN_IN_PAGE)
			.loginProcessingUrl(this.providers.callbacks())
			.authorizationEndpoint((endpoint) -> endpoint.authorizationRequestResolver(this.starts)
				.authorizationRequestRepository(this.states))
			.successHandler(providerTokens.droppedBefore(this.callbacks))
			.failureHandler(this.callbacks)
			.withObjectPostProcessor(new RefusedStarts(this.starts.refusals()))
			.withObjectPostProcessor(new CallbacksByGetOrHead(this.providers.callbackRequests()));
	}

	/**
	 * Gives the filter that starts a sign-in the handler that answers a refused start as
	 * the API answers a refusal, where it would answer {@code 500}.
	 */
	private static final class RefusedStarts implements ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter> {

		private final AuthenticationFailureHandler refusals;

		RefusedStarts(AuthenticationFailureHandler refusals) {
			this.refusals = refusals;
		}

		@Override
		public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
			filter.setAuthenticationFailureHandler(this.refusals);
			return filter;
		}

	}

	/**
	 * Lets the login finish a sign-in only at a callback the browser comes back to with
	 * {@code GET} or {@code HEAD}, as providers send it, where Spring Security's login
	 * takes any method.
	 */
	private static final class CallbacksByGetOrHead implements ObjectPostProcessor<OAuth2LoginAuthenticationFilter> {

		private final RequestMatcher callbacks;

		CallbacksByGetOrHead(RequestMatcher callbacks) {
			this.callbacks = callbacks;
		}

		@Override
		public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
			filter.setRequiresAuthenticationRequestMatcher(this.callbacks);
			return filter;
		}

	}

}
