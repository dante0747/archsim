package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Csrf;
import io.archsim.app.access.Principal;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceView;
import io.archsim.app.workspaces.Workspaces;

/**
 * Who is asking, as the web app shows it: the person, their workspaces and, for a browser
 * session, the CSRF token every change must carry.
 */
@Service
class Me {

	private final AccountRepository accounts;

	private final Workspaces workspaces;

	private final Csrf csrf;

	private final ArchsimProperties properties;

	Me(AccountRepository accounts, Workspaces workspaces, Csrf csrf, ArchsimProperties properties) {
		this.accounts = accounts;
		this.workspaces = workspaces;
		this.csrf = csrf;
		this.properties = properties;
	}

	/**
	 * Describes who is asking.
	 * @param principal who is asking
	 * @return the person, how they signed in, and their workspaces
	 * @throws ApiException {@code 404} when their account is gone
	 */
	View describe(Principal principal) {
		UserView user = this.accounts.user(principal.userId()).orElseThrow(() -> Access.notFound("Your account was"));
		String sessionId = principal.sessionId();
		return new View(this.properties.authMode().json(), principal.kind().json(), user, this.workspaces.of(principal),
				(sessionId != null) ? this.csrf.token(sessionId) : null);
	}

	/**
	 * Who is asking.
	 *
	 * @param mode {@code none} or {@code oauth}
	 * @param kind {@code local} (no sign-in), {@code session} (a signed-in browser) or
	 * {@code token}
	 * @param user the person
	 * @param workspaces the workspaces they belong to
	 * @param csrfToken what to send as {@code X-ArchSim-CSRF} with every change a session
	 * cookie authenticates; none for a token or without sign-in
	 */
	record View(String mode, String kind, UserView user, List<WorkspaceView> workspaces, @Nullable String csrfToken) {

	}

}
