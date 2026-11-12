package io.archsim.app.access;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * A browser session that signs its person in, as the security context holds it. Spring
 * Security names it by its person.
 *
 * @param userId the person
 * @param sessionId the session's id (the SHA-256 of its cookie), whose CSRF token a
 * change must carry
 */
record BrowserSession(String userId, String sessionId) implements AuthenticatedPrincipal {

	@Override
	public String getName() {
		return this.userId;
	}

}
