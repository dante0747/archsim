package io.archsim.app.access;

import org.jspecify.annotations.Nullable;

/**
 * Who is asking. Without sign-in it is the local user, owner of the default workspace;
 * with it, a browser session or an API token (from slice 3 of the port).
 *
 * @param userId the user the request acts for
 * @param kind how the request says who it is
 * @param ip the address the request comes from, as the history records it
 */
public record Principal(String userId, PrincipalKind kind, @Nullable String ip) {

	/** The user every request is without sign-in. */
	public static final String LOCAL_USER_ID = "local";

	/**
	 * The local user, asking from an address.
	 * @param ip the address, if known
	 * @return the principal
	 */
	public static Principal local(@Nullable String ip) {
		return new Principal(LOCAL_USER_ID, PrincipalKind.LOCAL, ip);
	}

}
