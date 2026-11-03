package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Visitor;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.web.ApiException;

/**
 * Starts signing in: a random state and PKCE verifier, kept in a signed cookie for ten
 * minutes, and the provider's sign-in page to send the browser to.
 */
@Service
class Logins {

	private final OAuthClient client;

	private final StateCookies states;

	private final RateLimits limits;

	Logins(OAuthClient client, StateCookies states, RateLimits limits) {
		this.client = client;
		this.states = states;
		this.limits = limits;
	}

	/**
	 * Starts signing in with a provider.
	 * @param provider the provider
	 * @param next the path to land on afterwards
	 * @param visitor where the request comes from: sign-ins are limited per address
	 * @return the redirect to the provider's page, with the state cookie
	 * @throws ApiException {@code 429} after too many sign-ins from one address
	 */
	Redirect start(OAuthProvider provider, @Nullable String next, Visitor visitor) {
		this.limits.enforce("ip:" + visitor.ipOrUnknown() + ":login", RateLimit.LOGINS_PER_IP,
				"Too many sign-in attempts.");
		SignInState state = this.states.start(provider.name(), next);
		return new Redirect(this.client.authorizationUrl(provider, state), List.of(this.states.setCookie(state)));
	}

}
