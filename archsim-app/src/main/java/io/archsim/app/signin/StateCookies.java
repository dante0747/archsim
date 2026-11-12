package io.archsim.app.signin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.SigningKeys;

/**
 * Where Spring Security's OAuth2 login keeps a sign-in between sending the browser to the
 * provider and its return: the sign-in state cookie, so that any instance finishes a
 * sign-in another started. Its value is the state as base64url JSON, a dot, and its HMAC
 * under the {@code oauth-state} key, as the Python API wrote it. Nobody without the
 * secret key can forge or change it, and it is good for ten minutes. A callback finds its
 * sign-in only with the state the cookie keeps.
 */
@Component
class StateCookies implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	/** The attribute of a sign-in that says where to land afterwards. */
	static final String NEXT = "next";

	/** How long a sign-in may take, from starting it to the provider's return. */
	static final long LIFETIME_SECONDS = 600;

	private static final String PURPOSE = "oauth-state";

	private static final String NONCE = "nonce";

	private static final double MILLIS = 1000.0;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SigningKeys keys;

	private final JsonMapper json;

	private final Clock clock;

	private final OAuthProviders providers;

	StateCookies(SigningKeys keys, JsonMapper json, Clock clock, OAuthProviders providers) {
		this.keys = keys;
		this.json = json;
		this.clock = clock;
		this.providers = providers;
	}

	/**
	 * Keeps a sign-in in the cookie, or clears the cookie when there is none.
	 * @param authorizationRequest the sign-in Spring Security starts, or {@code null}
	 * @param request the request that starts it
	 * @param response the redirect to the provider, which sets the cookie
	 */
	@Override
	public void saveAuthorizationRequest(@Nullable OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			clear(response);
			return;
		}
		SignInState state = new SignInState(attribute(authorizationRequest, OAuth2ParameterNames.REGISTRATION_ID),
				Objects.toString(authorizationRequest.getState(), ""),
				attribute(authorizationRequest, PkceParameterNames.CODE_VERIFIER),
				attribute(authorizationRequest, NEXT), attribute(authorizationRequest, OidcParameterNames.NONCE),
				now() + LIFETIME_SECONDS);
		response.addHeader(HttpHeaders.SET_COOKIE, BrowserCookie.STATE.set(sign(state), LIFETIME_SECONDS));
	}

	/**
	 * The sign-in a callback finishes: the cookie's, when it is genuine, has not expired,
	 * names a provider set up here, the one whose callback the browser came back to (so a
	 * provider's answer is never taken for another's), and keeps the state the provider
	 * sent back.
	 * @param request the callback
	 * @return the sign-in, or {@code null}
	 */
	@Override
	public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		String sent = request.getParameter(OAuth2ParameterNames.STATE);
		Optional<String> provider = OAuthProviders.callbackOf(request);
		return cookie(request).flatMap(this::verify)
			.filter((kept) -> provider.equals(Optional.of(kept.provider())))
			.filter((kept) -> sent != null && same(kept.state(), sent))
			.flatMap(this::signIn)
			.orElse(null);
	}

	/**
	 * The sign-in a callback finishes, clearing the cookie whatever it holds.
	 * @param request the callback
	 * @param response its answer
	 * @return the sign-in, or {@code null}
	 */
	@Override
	public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest signIn = loadAuthorizationRequest(request);
		clear(response);
		return signIn;
	}

	/**
	 * Clears the cookie, unless the response clears it already.
	 * @param response the answer to the callback
	 */
	void clear(HttpServletResponse response) {
		boolean cleared = response.getHeaders(HttpHeaders.SET_COOKIE)
			.stream()
			.anyMatch((header) -> header.startsWith(BrowserCookie.STATE_NAME + "="));
		if (!cleared) {
			response.addHeader(HttpHeaders.SET_COOKIE, BrowserCookie.STATE.cleared(this.clock.instant()));
		}
	}

	/**
	 * Where a callback's browser lands: where its sign-in started, as the cookie the
	 * request still carries says.
	 * @param request the callback
	 * @return a path on this site, {@code /} when the cookie says none
	 */
	String next(HttpServletRequest request) {
		return Landing.safePath(cookie(request).flatMap(this::verify).map(SignInState::next).orElse(null));
	}

	/**
	 * The cookie's value for a sign-in.
	 * @param state the sign-in
	 * @return the signed value
	 */
	String sign(SignInState state) {
		ObjectNode payload = this.json.createObjectNode()
			.put("p", state.provider())
			.put("s", state.state())
			.put("v", state.verifier())
			.put("n", state.next())
			.put(NONCE, state.nonce())
			.put("exp", state.expiresAt());
		String body = ENCODER.encodeToString(this.json.writeValueAsBytes(payload));
		return body + "." + ENCODER.encodeToString(mac(body));
	}

	/**
	 * The sign-in a cookie carries, if it is genuine and has not expired.
	 * @param cookie the cookie's value, if the browser sent one
	 * @return the sign-in, or empty
	 */
	Optional<SignInState> verify(@Nullable String cookie) {
		int dot = (cookie != null) ? cookie.indexOf('.') : -1;
		if (cookie == null || dot <= 0 || dot == cookie.length() - 1) {
			return Optional.empty();
		}
		String body = cookie.substring(0, dot);
		try {
			byte[] sent = Base64.getUrlDecoder().decode(cookie.substring(dot + 1));
			if (!MessageDigest.isEqual(sent, mac(body))) {
				return Optional.empty();
			}
			return state(this.json.readTree(Base64.getUrlDecoder().decode(body)));
		}
		catch (IllegalArgumentException | JacksonException ex) {
			return Optional.empty();
		}
	}

	/**
	 * The sign-in Spring Security started, rebuilt from the provider's registration and
	 * what the cookie kept.
	 * @param kept what the cookie kept
	 * @return the sign-in, or empty when its provider is not set up here
	 */
	private Optional<OAuth2AuthorizationRequest> signIn(SignInState kept) {
		return this.providers.find(kept.provider()).map((provider) -> signIn(provider.registration(), kept));
	}

	private static OAuth2AuthorizationRequest signIn(ClientRegistration registration, SignInState kept) {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri(Objects.requireNonNull(registration.getProviderDetails().getAuthorizationUri(),
					"every provider has a sign-in page"))
			.clientId(registration.getClientId())
			.redirectUri(registration.getRedirectUri())
			.scopes(registration.getScopes())
			.state(kept.state())
			.attributes((attributes) -> attributes.putAll(attributes(kept)))
			.build();
	}

	private static Map<String, Object> attributes(SignInState kept) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put(OAuth2ParameterNames.REGISTRATION_ID, kept.provider());
		attributes.put(PkceParameterNames.CODE_VERIFIER, kept.verifier());
		attributes.put(NEXT, kept.next());
		if (!kept.nonce().isEmpty()) {
			attributes.put(OidcParameterNames.NONCE, kept.nonce());
		}
		return attributes;
	}

	private Optional<SignInState> state(JsonNode payload) {
		double expiresAt = payload.path("exp").asDouble(0);
		if (!payload.isObject() || expiresAt < now()) {
			return Optional.empty();
		}
		return Optional.of(new SignInState(payload.path("p").asString(""), payload.path("s").asString(""),
				payload.path("v").asString(""), payload.path("n").asString(""), payload.path(NONCE).asString(""),
				expiresAt));
	}

	private static Optional<String> cookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (BrowserCookie.STATE_NAME.equals(cookie.getName())) {
				return Optional.of(cookie.getValue());
			}
		}
		return Optional.empty();
	}

	private static String attribute(OAuth2AuthorizationRequest authorizationRequest, String name) {
		return Objects.toString(authorizationRequest.getAttribute(name), "");
	}

	private static boolean same(String kept, String sent) {
		return MessageDigest.isEqual(kept.getBytes(StandardCharsets.UTF_8), sent.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] mac(String body) {
		return this.keys.mac(PURPOSE, body.getBytes(StandardCharsets.US_ASCII));
	}

	private double now() {
		return this.clock.millis() / MILLIS;
	}

}
