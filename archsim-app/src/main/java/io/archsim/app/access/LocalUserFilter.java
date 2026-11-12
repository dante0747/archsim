package io.archsim.app.access;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Without sign-in, every request is the local user, owner of the default workspace: this
 * filter says so in Spring Security's context, in place of the API token and session
 * filters.
 */
public final class LocalUserFilter extends OncePerRequestFilter {

	private final SecurityContextHolderStrategy contexts;

	/**
	 * A filter that signs every request in as the local user.
	 * @param contexts where Spring Security keeps the request's context
	 */
	public LocalUserFilter(SecurityContextHolderStrategy contexts) {
		this.contexts = contexts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		SecurityContext context = this.contexts.createEmptyContext();
		context.setAuthentication(new PreAuthenticatedAuthenticationToken(new LocalUser(), "", List.of()));
		this.contexts.setContext(context);
		chain.doFilter(request, response);
	}

}
