package io.archsim.app.access;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The local user, owner of the default workspace, whom every request is without sign-in,
 * as the security context holds it.
 */
record LocalUser() implements AuthenticatedPrincipal {

	@Override
	public String getName() {
		return Principal.LOCAL_USER_ID;
	}

}
