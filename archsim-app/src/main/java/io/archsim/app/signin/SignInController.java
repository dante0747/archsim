package io.archsim.app.signin;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Csrf;
import io.archsim.app.access.Principal;
import io.archsim.app.access.Visitor;
import io.archsim.app.web.QueryParameters;

/**
 * Signing in and out. {@code GET /auth/config} says how people sign in here; with
 * {@code ARCHSIM_AUTH_MODE=oauth}, {@code /auth/<provider>/login} and
 * {@code /auth/<provider>/callback} sign in with GitHub or Google and
 * {@code POST /auth/logout} ends the session.
 */
@RestController
@RequestMapping("/auth")
class SignInController {

	private static final int MAX_NEXT = 500;

	private final OAuthProviders providers;

	private final Logins logins;

	private final Callbacks callbacks;

	private final Sessions sessions;

	SignInController(OAuthProviders providers, Logins logins, Callbacks callbacks, Sessions sessions) {
		this.providers = providers;
		this.logins = logins;
		this.callbacks = callbacks;
		this.sessions = sessions;
	}

	/**
	 * How people sign in here: {@code none} (local, no sign-in) or {@code oauth} with its
	 * providers.
	 * @return the mode and providers
	 */
	@GetMapping("/config")
	AuthConfig config() {
		return this.providers.config();
	}

	/**
	 * Start signing in with {@code github} or {@code google}: redirects to the provider.
	 * @param provider the provider
	 * @param next the path to land on after
	 * @param visitor where the request comes from
	 * @return {@code 302} to the provider's sign-in page
	 */
	@GetMapping("/{provider}/login")
	ResponseEntity<Void> login(@PathVariable String provider,
			@RequestParam(required = false) @Parameter(description = "Path to land on after",
					schema = @Schema(type = "string", maxLength = MAX_NEXT)) @Nullable List<String> next,
			Visitor visitor) {
		String path = QueryParameters.text(next, "next", MAX_NEXT).orElse(null);
		return this.logins.start(this.providers.require(provider), path, visitor).answer();
	}

	/**
	 * Where the provider sends the browser back. Sets the session cookie and redirects to
	 * the page sign-in started from; on failure, to {@code /signin?error=<reason>}.
	 * @param provider the provider
	 * @param callback the code, state and error the provider sent
	 * @param stateCookie the sign-in's state cookie
	 * @param visitor where the request comes from
	 * @return {@code 302}
	 */
	@GetMapping("/{provider}/callback")
	ResponseEntity<Void> callback(@PathVariable String provider, Callbacks.Callback callback,
			@CookieValue(name = BrowserCookie.STATE_NAME, required = false) @Nullable String stateCookie,
			Visitor visitor) {
		return this.callbacks.finish(this.providers.require(provider), callback, stateCookie, visitor).answer();
	}

	/**
	 * End this browser's session ({@code everywhere}: all of this account's), at once on
	 * every instance. Needs the CSRF header like any change. Signed out already: nothing
	 * to do.
	 * @param everywhere whether to end every session of the account
	 * @param cookie the session cookie
	 * @param csrfHeader the CSRF header
	 * @return {@code 204}, clearing the cookies
	 */
	@PostMapping("/logout")
	ResponseEntity<Void> logout(
			@RequestParam(required = false) @Parameter(description = "End every session of this account",
					schema = @Schema(type = "boolean", defaultValue = "false")) @Nullable List<String> everywhere,
			@CookieValue(name = Principal.SESSION_COOKIE, required = false) @Nullable String cookie,
			@RequestHeader(name = Csrf.HEADER, required = false) @Nullable String csrfHeader) {
		boolean all = QueryParameters.flag(everywhere, "everywhere").orElse(false);
		if (cookie != null && !cookie.isEmpty() && this.providers.hosted()) {
			if (all) {
				this.sessions.endEverywhere(cookie, csrfHeader);
			}
			else {
				this.sessions.end(cookie, csrfHeader);
			}
		}
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, this.sessions.clearCookies().toArray(String[]::new))
			.build();
	}

}
