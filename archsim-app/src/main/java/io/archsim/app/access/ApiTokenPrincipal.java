package io.archsim.app.access;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * Whose personal API token a request carries, and what it allows, as the token filter
 * puts it in the security context. Roles are workspace roles, which the features check,
 * so it grants no authorities of Spring Security's.
 *
 * @param userId the token's user
 * @param grant what the token allows
 */
record ApiTokenPrincipal(String userId, TokenGrant grant) implements OAuth2AuthenticatedPrincipal {

	@Override
	public String getName() {
		return this.userId;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return Map.of();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

}
