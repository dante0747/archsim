package io.archsim.app.access;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;

/**
 * Who is asking. Without sign-in it is the local user, owner of the default workspace;
 * with it, a browser session or a personal API token.
 *
 * @param userId the user the request acts for
 * @param kind how the request says who it is
 * @param ip the address the request comes from, as the history records it
 * @param sessionId the browser session's id (the SHA-256 of its cookie), for a session
 * @param token what the API token allows, for a token
 */
public record Principal(String userId, PrincipalKind kind, @Nullable String ip, @Nullable String sessionId,
		@Nullable TokenGrant token) {

	/** The user every request is without sign-in. */
	public static final String LOCAL_USER_ID = "local";

	/** The cookie that carries a browser session's secret. */
	public static final String SESSION_COOKIE = "__Host-archsim_session";

	/**
	 * A principal whose kind matches what it carries: a session id for a session, a grant
	 * for a token, neither without sign-in.
	 * @param userId the user
	 * @param kind how the request says who it is
	 * @param ip the address
	 * @param sessionId the session's id, for a session
	 * @param token the token's grant, for a token
	 */
	public Principal {
		boolean consistent = switch (kind) {
			case LOCAL -> sessionId == null && token == null;
			case SESSION -> sessionId != null && token == null;
			case TOKEN -> sessionId == null && token != null;
		};
		if (!consistent) {
			throw new IllegalArgumentException("a " + kind.json() + " principal carries a session id only as a "
					+ "session and a grant only as a token");
		}
	}

	/**
	 * The local user, asking from an address.
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal local(@Nullable String ip) {
		return new Principal(LOCAL_USER_ID, PrincipalKind.LOCAL, ip, null, null);
	}

	/**
	 * A user signed in with a browser session.
	 * @param userId the user
	 * @param sessionId the session's id
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal session(String userId, String sessionId, @Nullable String ip) {
		return new Principal(userId, PrincipalKind.SESSION, ip, sessionId, null);
	}

	/**
	 * A user's personal API token.
	 * @param userId the token's user
	 * @param token what the token allows
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal token(String userId, TokenGrant token, @Nullable String ip) {
		return new Principal(userId, PrincipalKind.TOKEN, ip, null, token);
	}

	/**
	 * The one workspace a token is limited to: it sees no other.
	 * @return the workspace's id, or empty when the principal is not limited to one
	 */
	public Optional<String> tokenWorkspaceId() {
		return Optional.ofNullable(this.token).map(TokenGrant::workspaceId);
	}

	/**
	 * Whether this is a read-scoped token: viewer at most, wherever its user has a role.
	 * @return {@code true} for a {@code read} token
	 */
	public boolean readOnly() {
		return this.token != null && this.token.readOnly();
	}

	/**
	 * Whether the principal may have a role in a workspace at all: a token limited to one
	 * workspace sees no other.
	 * @param workspaceId the workspace
	 * @return {@code false} for a workspace a token does not name
	 */
	public boolean sees(String workspaceId) {
		return tokenWorkspaceId().map(workspaceId::equals).orElse(true);
	}

	/**
	 * The principal's role where its user has a role: viewer for a read-only token.
	 * @param membership the user's own role there
	 * @return the principal's role
	 */
	public WorkspaceRole roleFor(WorkspaceRole membership) {
		return readOnly() ? WorkspaceRole.VIEWER : membership;
	}

	/**
	 * Refuses API tokens: managing access itself (tokens, invitations, workspaces) takes
	 * a person.
	 * @throws ApiException {@code 403} for a token
	 */
	public void requirePerson() {
		if (this.kind == PrincipalKind.TOKEN) {
			throw new ApiException(HttpStatus.FORBIDDEN, "API tokens cannot do this: sign in to the web app instead.",
					List.of());
		}
	}

}
