package io.archsim.app.access;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;

/**
 * Who is asking. Without sign-in it is the local user, owner of the default workspace;
 * with it, a browser session or a personal API token. A browser session may be a
 * sandbox's guest's, a visitor who tries ArchSim without signing in (ADR-0079): they use
 * their sandbox, but nothing that takes an account of one's own.
 *
 * @param userId the user the request acts for
 * @param kind how the request says who it is
 * @param ip the address the request comes from, as the history records it
 * @param sessionId the browser session's id (the SHA-256 of its cookie), for a session
 * @param token what the API token allows, for a token
 * @param guest whether the session is a sandbox's guest's
 */
public record Principal(String userId, PrincipalKind kind, @Nullable String ip, @Nullable String sessionId,
		@Nullable TokenGrant token, boolean guest) {

	/** The user every request is without sign-in. */
	public static final String LOCAL_USER_ID = "local";

	/** The cookie that carries a browser session's secret. */
	public static final String SESSION_COOKIE = "__Host-archsim_session";

	/** What a sandbox's guest is told when they try what takes an account. */
	public static final String GUESTS_CANNOT = "A sandbox's guest cannot do this: sign in first.";

	/**
	 * A principal whose kind matches what it carries: a session id for a session, a grant
	 * for a token, neither without sign-in; only a session may be a guest's.
	 * @param userId the user
	 * @param kind how the request says who it is
	 * @param ip the address
	 * @param sessionId the session's id, for a session
	 * @param token the token's grant, for a token
	 * @param guest whether the session is a guest's
	 */
	public Principal {
		boolean consistent = switch (kind) {
			case LOCAL -> sessionId == null && token == null && !guest;
			case SESSION -> sessionId != null && token == null;
			case TOKEN -> sessionId == null && token != null && !guest;
		};
		if (!consistent) {
			throw new IllegalArgumentException("a " + kind.json() + " principal carries a session id only as a "
					+ "session, a grant only as a token, and is a guest only as a session");
		}
	}

	/**
	 * The local user, asking from an address.
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal local(@Nullable String ip) {
		return new Principal(LOCAL_USER_ID, PrincipalKind.LOCAL, ip, null, null, false);
	}

	/**
	 * A user signed in with a browser session.
	 * @param userId the user
	 * @param sessionId the session's id
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal session(String userId, String sessionId, @Nullable String ip) {
		return new Principal(userId, PrincipalKind.SESSION, ip, sessionId, null, false);
	}

	/**
	 * A sandbox's guest, signed in with the browser session that started the sandbox.
	 * @param userId the guest
	 * @param sessionId the session's id
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal guest(String userId, String sessionId, @Nullable String ip) {
		return new Principal(userId, PrincipalKind.SESSION, ip, sessionId, null, true);
	}

	/**
	 * A user's personal API token.
	 * @param userId the token's user
	 * @param token what the token allows
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal token(String userId, TokenGrant token, @Nullable String ip) {
		return new Principal(userId, PrincipalKind.TOKEN, ip, null, token, false);
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
	 * Refuses API tokens and sandboxes' guests: managing access itself (tokens,
	 * invitations, workspaces) takes a person with an account of their own.
	 * @throws ApiException {@code 403} for a token or a guest
	 */
	public void requirePerson() {
		if (this.kind == PrincipalKind.TOKEN) {
			throw new ApiException(HttpStatus.FORBIDDEN, "API tokens cannot do this: sign in to the web app instead.",
					List.of());
		}
		requireAccount();
	}

	/**
	 * Refuses a sandbox's guest, for what takes an account of one's own: managing access,
	 * and share links, which would publish what an anonymous visitor wrote.
	 * @throws ApiException {@code 403} for a guest
	 */
	public void requireAccount() {
		if (this.guest) {
			throw new ApiException(HttpStatus.FORBIDDEN, GUESTS_CANNOT, List.of());
		}
	}

	/**
	 * Refuses a read-only API token, for a change it may not make.
	 * @throws ApiException {@code 403} for a read-only token
	 */
	public void requireWritable() {
		if (readOnly()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "This API token can only read: use a token with write scope.",
					List.of());
		}
	}

}
