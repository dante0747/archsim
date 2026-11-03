package io.archsim.app.signin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Secrets;
import io.archsim.app.access.SigningKeys;

/**
 * The sign-in state cookie: the state as base64url JSON, a dot, and its HMAC under the
 * {@code oauth-state} key, as the Python API writes it, so either API reads the other's.
 * Nobody without the secret key can forge or change it, and it is good for ten minutes.
 */
@Component
class StateCookies {

	/** How long a sign-in may take, from starting it to the provider's return. */
	static final long LIFETIME_SECONDS = 600;

	private static final String PURPOSE = "oauth-state";

	/** The characters of the second secret a PKCE verifier adds: 83 in all. */
	private static final int VERIFIER_EXTRA = 40;

	private static final double MILLIS = 1000.0;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SigningKeys keys;

	private final JsonMapper json;

	private final Clock clock;

	StateCookies(SigningKeys keys, JsonMapper json, Clock clock) {
		this.keys = keys;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * A new sign-in: a random state and PKCE verifier, good for ten minutes.
	 * @param provider the provider it signs in with
	 * @param next where to land afterwards, made safe
	 * @return the state
	 */
	SignInState start(String provider, @Nullable String next) {
		String verifier = Secrets.newSecret("") + Secrets.newSecret("").substring(0, VERIFIER_EXTRA);
		return new SignInState(provider, Secrets.newSecret(""), verifier, Landing.safePath(next),
				now() + LIFETIME_SECONDS);
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
			.put("exp", state.expiresAt());
		String body = ENCODER.encodeToString(this.json.writeValueAsBytes(payload));
		return body + "." + ENCODER.encodeToString(mac(body));
	}

	/**
	 * The header that hands a sign-in's state cookie to the browser.
	 * @param state the sign-in
	 * @return the {@code Set-Cookie} header's value
	 */
	String setCookie(SignInState state) {
		return BrowserCookie.STATE.set(sign(state), LIFETIME_SECONDS);
	}

	/**
	 * The header that clears the state cookie once the browser is back.
	 * @return the {@code Set-Cookie} header's value
	 */
	String clearCookie() {
		return BrowserCookie.STATE.cleared(this.clock.instant());
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

	private Optional<SignInState> state(JsonNode payload) {
		double expiresAt = payload.path("exp").asDouble(0);
		if (!payload.isObject() || expiresAt < now()) {
			return Optional.empty();
		}
		return Optional.of(new SignInState(payload.path("p").asString(""), payload.path("s").asString(""),
				payload.path("v").asString(""), payload.path("n").asString(""), expiresAt));
	}

	private byte[] mac(String body) {
		return this.keys.mac(PURPOSE, body.getBytes(StandardCharsets.US_ASCII));
	}

	private double now() {
		return this.clock.millis() / MILLIS;
	}

}
