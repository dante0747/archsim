package io.archsim.app.signin;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.access.Visitor;

/**
 * {@code POST /auth/sandbox}: try ArchSim without signing in (ADR-0079), where the server
 * offers it ({@code ARCHSIM_SANDBOXES}).
 */
@RestController
@RequestMapping("/auth")
class SandboxController {

	private final Sandboxes sandboxes;

	SandboxController(Sandboxes sandboxes) {
		this.sandboxes = sandboxes;
	}

	/**
	 * Start a sandbox: a guest account with no identity or email, which owns a sandbox
	 * workspace (with the sandbox's limits), and a session for this browser, which sets
	 * the session and CSRF cookies. The guest, the sandbox and all in it are deleted
	 * {@link Guests#LIFETIME} later. Spring Security's CSRF filter has checked the CSRF
	 * header against its cookie, which {@code GET /auth/config} gives a browser.
	 * @param visitor where the browser is, whose starts are limited
	 * ({@link io.archsim.app.limits.RateLimit#SANDBOXES_PER_IP})
	 * @param cookie the session cookie, if the browser is signed in already
	 * @return {@code 201} with the guest as {@code GET /auth/me} describes them
	 */
	@PostMapping("/sandbox")
	ResponseEntity<Me.View> start(Visitor visitor,
			@CookieValue(name = Principal.SESSION_COOKIE, required = false) @Nullable String cookie) {
		StartedSandbox started = this.sandboxes.start(visitor, cookie);
		return ResponseEntity.status(HttpStatus.CREATED)
			.header(HttpHeaders.SET_COOKIE, started.cookies().toArray(String[]::new))
			.body(started.me());
	}

}
