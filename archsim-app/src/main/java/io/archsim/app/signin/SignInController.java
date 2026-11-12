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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.QueryParameters;

/**
 * Signing in and out. {@code GET /auth/config} says how people sign in here; with
 * {@code ARCHSIM_AUTH_MODE=oauth}, {@code /auth/<provider>/login} and
 * {@code /auth/<provider>/callback} sign in with GitHub or Google and
 * {@code POST /auth/logout} ends the session. Spring Security's OAuth2 login answers the
 * sign-in paths of the providers set up here before they reach this controller
 * ({@link OAuthSignIn}); this answers the others, and documents them all.
 */
@RestController
@RequestMapping("/auth")
class SignInController {

	/** The longest code a provider sends. */
	private static final int MAX_CODE = 2000;

	/** The longest state a provider sends back. */
	private static final int MAX_STATE = 200;

	/** The longest error a provider sends back. */
	private static final int MAX_ERROR = 200;

	private final OAuthProviders providers;

	private final Sessions sessions;

	SignInController(OAuthProviders providers, Sessions sessions) {
		this.providers = providers;
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
	 * Start signing in with {@code github} or {@code google}: redirects to the provider
	 * ({@link SignInStarts}). Reached only when the provider is not set up here.
	 * @param provider the provider
	 * @param next the path to land on after
	 * @return never
	 * @throws ApiException {@code 404}: the provider is not set up here
	 */
	@GetMapping("/{provider}/login")
	ResponseEntity<Void> login(@PathVariable String provider,
			@RequestParam(required = false) @Parameter(description = "Path to land on after",
					schema = @Schema(type = "string", maxLength = Landing.MAX_PATH)) @Nullable List<String> next) {
		// a next too long is refused 422 first, as for a provider that is set up
		QueryParameters.text(next, "next", Landing.MAX_PATH);
		throw this.providers.notSetUp(provider);
	}

	/**
	 * Where the provider sends the browser back. Sets the session cookie and redirects to
	 * the page sign-in started from; on failure, to {@code /signin?error=<reason>}
	 * ({@link Callbacks}). Reached only when the provider is not set up here.
	 * @param provider the provider
	 * @param code the code to exchange, if the person agreed
	 * @param state the sign-in's state
	 * @param error why the provider refused, if it did
	 * @return never
	 * @throws ApiException {@code 404}: the provider is not set up here
	 */
	@GetMapping("/{provider}/callback")
	ResponseEntity<Void> callback(@PathVariable String provider,
			@RequestParam(required = false) @Parameter(
					schema = @Schema(type = "string", maxLength = MAX_CODE)) @Nullable List<String> code,
			@RequestParam(required = false) @Parameter(
					schema = @Schema(type = "string", maxLength = MAX_STATE)) @Nullable List<String> state,
			@RequestParam(required = false) @Parameter(
					schema = @Schema(type = "string", maxLength = MAX_ERROR)) @Nullable List<String> error) {
		// a value too long is refused 422 before the 404, as FastAPI refused it
		QueryParameters.text(code, "code", MAX_CODE);
		QueryParameters.text(state, "state", MAX_STATE);
		QueryParameters.text(error, "error", MAX_ERROR);
		throw this.providers.notSetUp(provider);
	}

	/**
	 * End this browser's session ({@code everywhere}: all of this account's), at once on
	 * every instance. Spring Security's CSRF filter has checked the CSRF header, as for
	 * any change a session makes. Signed out already: nothing to do.
	 * @param everywhere whether to end every session of the account
	 * @param cookie the session cookie
	 * @return {@code 204}, clearing the cookies
	 */
	@PostMapping("/logout")
	ResponseEntity<Void> logout(
			@RequestParam(required = false) @Parameter(description = "End every session of this account",
					schema = @Schema(type = "boolean", defaultValue = "false")) @Nullable List<String> everywhere,
			@CookieValue(name = Principal.SESSION_COOKIE, required = false) @Nullable String cookie) {
		boolean all = QueryParameters.flag(everywhere, "everywhere").orElse(false);
		if (cookie != null && !cookie.isEmpty() && this.providers.hosted()) {
			if (all) {
				this.sessions.endEverywhere(cookie);
			}
			else {
				this.sessions.end(cookie);
			}
		}
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, this.sessions.clearCookies().toArray(String[]::new))
			.build();
	}

}
