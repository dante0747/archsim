package io.archsim.app.signin;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.web.WebConfiguration;

/**
 * Whether this server lets visitors try ArchSim in a sandbox without signing in
 * (ADR-0079): only with sign-in and {@code ARCHSIM_SANDBOXES}. Where it does, a start,
 * {@code POST /auth/sandbox}, signs no one in yet but sets a session cookie, so Spring
 * Security's CSRF filter checks it as it checks a session's changes: another site cannot
 * make a visitor's browser start a sandbox, and so sign it out of its account. A browser
 * holds the token from {@code GET /auth/config}.
 */
@Component
public final class SandboxOffer {

	/** Where a sandbox starts. */
	private static final RequestMatcher STARTS = PathPatternRequestMatcher.withDefaults()
		.matcher(HttpMethod.POST, WebConfiguration.PREFIX + "/auth/sandbox");

	private final boolean offered;

	SandboxOffer(ArchsimProperties properties, SignInProperties signIn) {
		this.offered = properties.hosted() && signIn.sandboxes();
	}

	/**
	 * Whether a visitor may try ArchSim here without signing in.
	 * @return {@code true} with sign-in and {@code ARCHSIM_SANDBOXES=true}
	 */
	boolean offered() {
		return this.offered;
	}

	/**
	 * The requests that start a sandbox here, which must send the browser's CSRF token.
	 * @return {@code POST /auth/sandbox}, or empty where sandboxes are not offered
	 */
	public Optional<RequestMatcher> starts() {
		return this.offered ? Optional.of(STARTS) : Optional.empty();
	}

	/**
	 * The CSRF token a browser starts a sandbox with, where they are offered: read (and,
	 * for a browser that has none, made and set in its cookie) only there.
	 * @param csrfToken the request's CSRF token, if Spring Security keeps one
	 * @return the token's value, or {@code null} where sandboxes are not offered
	 */
	@Nullable String csrfTokenFor(@Nullable CsrfToken csrfToken) {
		if (!this.offered || csrfToken == null) {
			return null;
		}
		return csrfToken.getToken();
	}

}
