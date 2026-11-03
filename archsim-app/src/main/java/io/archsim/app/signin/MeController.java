package io.archsim.app.signin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;

/**
 * {@code GET /auth/me}: who is asking.
 */
@RestController
@RequestMapping("/auth")
class MeController {

	private final Me me;

	MeController(Me me) {
		this.me = me;
	}

	/**
	 * The signed-in person, their workspaces and their session's CSRF token.
	 * @param principal who is asking
	 * @return who it is
	 */
	@GetMapping("/me")
	Me.View me(Principal principal) {
		return this.me.describe(principal);
	}

}
