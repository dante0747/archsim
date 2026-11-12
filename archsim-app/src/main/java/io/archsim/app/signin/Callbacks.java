package io.archsim.app.signin;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import io.archsim.app.access.Visitors;

/**
 * Finishes signing in when the provider sends the browser back to
 * {@code /auth/<provider>/callback}, once Spring Security's OAuth2 login has checked the
 * state against the cookie's, exchanged the code with the verifier and read the account:
 * the person behind the account is found or created, a session starts, and the browser
 * lands where sign-in started. A failure lands on the sign-in page, saying why
 * ({@code /signin?error=<reason>}).
 */
@Component
class Callbacks implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Callbacks.class);

	/**
	 * The OAuth2 login's errors for a callback that does not return a sign-in this
	 * browser started: no code or state, or no state cookie that keeps the state.
	 */
	private static final Set<String> NOT_RETURNED = Set.of(OAuth2ErrorCodes.INVALID_REQUEST,
			"authorization_request_not_found", "invalid_state_parameter");

	private final Accounts accounts;

	private final Sessions sessions;

	private final StateCookies states;

	private final Visitors visitors;

	Callbacks(Accounts accounts, Sessions sessions, StateCookies states, Visitors visitors) {
		this.accounts = accounts;
		this.sessions = sessions;
		this.states = states;
		this.visitors = visitors;
	}

	/**
	 * Signs the account in: a session, with its cookies, and the way back to where
	 * sign-in started.
	 * @param request the callback
	 * @param response its answer
	 * @param authentication the account Spring Security read
	 * @throws IOException when the answer cannot be written
	 */
	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		if (!(authentication instanceof OAuth2AuthenticationToken signedIn)
				|| !(signedIn.getPrincipal() instanceof ProviderAccount account)) {
			throw new IllegalStateException("OAuth2 login signed in no provider account: " + authentication);
		}
		try {
			String userId = this.accounts.signIn(signedIn.getAuthorizedClientRegistrationId(), account.profile());
			List<String> cookies = this.sessions.start(userId, this.visitors.of(request));
			// the cookie is signed, but where to land is checked again on the way out
			new Redirect(this.states.next(request), cookies).send(response);
		}
		catch (SignInFailure ex) {
			onAuthenticationFailure(request, response, ex);
		}
	}

	/**
	 * Lands on the sign-in page, saying why sign-in failed, and clears the state cookie.
	 * @param request the callback
	 * @param response its answer
	 * @param exception why it failed
	 * @throws IOException when the answer cannot be written
	 */
	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		SignInFailure failure = failure(request.getParameter(OAuth2ParameterNames.ERROR), exception);
		if (failure.atTheProvider()) {
			LOGGER.warn("Sign-in with {} failed at the provider: {}",
					OAuthProviders.callbackOf(request).orElse("a provider"), reason(exception));
		}
		this.states.clear(response);
		new Redirect(failure.page(), List.of()).send(response);
	}

	/**
	 * Why sign-in failed: the person said no at the provider; the callback returns no
	 * sign-in of this browser's; the account is disabled; or the provider failed.
	 * @param error the error the provider sent back, if any
	 * @param exception what Spring Security, or reading the account, threw
	 * @return the failure
	 */
	private static SignInFailure failure(@Nullable String error, AuthenticationException exception) {
		if (error != null && !error.isEmpty()) {
			return SignInFailure.denied();
		}
		if (exception instanceof SignInFailure failure) {
			return failure;
		}
		if (exception instanceof OAuth2AuthenticationException oauth
				&& NOT_RETURNED.contains(oauth.getError().getErrorCode())) {
			return SignInFailure.state();
		}
		return SignInFailure.provider(reason(exception));
	}

	/**
	 * What went wrong, for the log: the OAuth 2 error's code, or the failure's message.
	 * @param exception the failure
	 * @return the reason
	 */
	private static String reason(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationException oauth) {
			return oauth.getError().getErrorCode();
		}
		return String.valueOf(exception.getMessage());
	}

	/**
	 * What the provider sent back, from the callback's query.
	 *
	 * @param code the code to exchange, if the person agreed
	 * @param state the sign-in's state
	 * @param error why the provider refused, if it did
	 */
	record Callback(@Nullable String code, @Nullable String state, @Nullable String error) {

	}

}
