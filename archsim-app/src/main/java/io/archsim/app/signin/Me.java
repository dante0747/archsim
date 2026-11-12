package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.PrincipalKind;
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

	private final ArchsimProperties properties;

	Me(AccountRepository accounts, Workspaces workspaces, ArchsimProperties properties) {
		this.accounts = accounts;
		this.workspaces = workspaces;
		this.properties = properties;
	}

	/**
	 * Describes who is asking.
	 * @param principal who is asking
	 * @param csrfToken the request's CSRF token, read (and, for a browser that has none,
	 * made and set in its cookie) only for a browser session
	 * @return the person, how they signed in, their workspaces and, for a browser
	 * session, its CSRF token
	 * @throws ApiException {@code 404} when their account is gone
	 */
	View describe(Principal principal, @Nullable CsrfToken csrfToken) {
		UserView user = this.accounts.user(principal.userId()).orElseThrow(() -> Access.notFound("Your account was"));
		return new View(this.properties.authMode().json(), principal.kind().json(), user, this.workspaces.of(principal),
				csrfTokenFor(principal, csrfToken));
	}

	/**
	 * The CSRF token a browser session sends back with its changes; an API token and the
	 * local user need none, and are given none, so no CSRF cookie is set for them.
	 * @param principal who is asking
	 * @param csrfToken the request's CSRF token, if Spring Security keeps one
	 * @return the token's value, for a browser session only
	 */
	private static @Nullable String csrfTokenFor(Principal principal, @Nullable CsrfToken csrfToken) {
		if (principal.kind() != PrincipalKind.SESSION || csrfToken == null) {
			return null;
		}
		return csrfToken.getToken();
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
