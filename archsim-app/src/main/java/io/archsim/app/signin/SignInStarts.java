package io.archsim.app.signin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.Visitors;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.ErrorResponses;
import io.archsim.app.web.QueryParameters;
import io.archsim.app.web.RequestInvalidException;
import io.archsim.app.web.WebConfiguration;

/**
 * Starts signing in at {@code GET /auth/<provider>/login}, for Spring Security's OAuth2
 * login: Spring's sign-in (a random state, a PKCE verifier, and the OpenID Connect nonce
 * for Google) with the provider's own parameters and the path to land on afterwards. A
 * {@code next} over 500 characters is refused {@code 422} first, as FastAPI refused it;
 * then a provider that is not set up is left to {@link SignInController}, which refuses
 * it {@code 404}; then sign-ins are limited per address ({@code 429}).
 */
@Component
class SignInStarts implements OAuth2AuthorizationRequestResolver {

	private static final String PROVIDER = "registrationId";

	private static final String LOGIN = WebConfiguration.PREFIX + "/auth/{" + PROVIDER + "}/login";

	/**
	 * The sign-in paths, which take {@code HEAD} as Spring MVC's {@code GET} routes do.
	 */
	private static final RequestMatcher LOGINS = new OrRequestMatcher(
			PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, LOGIN),
			PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, LOGIN));

	private final OAuthProviders providers;

	private final RateLimits limits;

	private final Visitors visitors;

	private final StartRefusals refusals;

	private final DefaultOAuth2AuthorizationRequestResolver signIns;

	SignInStarts(OAuthProviders providers, RateLimits limits, Visitors visitors, JsonMapper json) {
		this.providers = providers;
		this.limits = limits;
		this.visitors = visitors;
		this.refusals = new StartRefusals(new ErrorResponses(json));
		this.signIns = new DefaultOAuth2AuthorizationRequestResolver(providers.registrations(),
				WebConfiguration.PREFIX + "/auth");
	}

	/**
	 * The sign-in a request starts.
	 * @param request the request
	 * @return the sign-in, or {@code null} when the request starts none here
	 * @throws RequestInvalidException {@code 422} when {@code next} is too long
	 * @throws ApiException {@code 429} after too many sign-ins from one address
	 */
	@Override
	public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		RequestMatcher.MatchResult login = LOGINS.matcher(request);
		if (!login.isMatch()) {
			return null;
		}
		return resolve(request, login.getVariables().getOrDefault(PROVIDER, ""));
	}

	/**
	 * The sign-in a request starts with a provider.
	 * @param request the request
	 * @param registrationId the provider
	 * @return the sign-in, or {@code null} when the provider is not set up here
	 * @throws RequestInvalidException {@code 422} when {@code next} is too long
	 * @throws ApiException {@code 429} after too many sign-ins from one address
	 */
	@Override
	public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
		String[] given = request.getParameterValues(StateCookies.NEXT);
		String next = QueryParameters.text((given != null) ? List.of(given) : null, StateCookies.NEXT, Landing.MAX_PATH)
			.orElse(null);
		OAuthProvider provider = this.providers.find(registrationId).orElse(null);
		if (provider == null) {
			return null;
		}
		this.limits.enforce("ip:" + this.visitors.of(request).ipOrUnknown() + ":login", RateLimit.LOGINS_PER_IP,
				"Too many sign-in attempts.");
		OAuth2AuthorizationRequest signIn = this.signIns.resolve(request, registrationId);
		if (signIn == null) {
			return null;
		}
		return OAuth2AuthorizationRequest.from(signIn)
			.additionalParameters((parameters) -> parameters.putAll(provider.signInParameters()))
			.attributes((attributes) -> attributes.put(StateCookies.NEXT, Landing.safePath(next)))
			.build();
	}

	/**
	 * How a refused start is answered: as the API answers a refusal.
	 * @return the handler Spring Security's redirect filter calls
	 */
	AuthenticationFailureHandler refusals() {
		return this.refusals;
	}

}
