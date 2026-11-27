package io.archsim.app.access;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.ErrorResponses;

/**
 * Refuses a body larger than 256 KB from a sandbox's guest ({@code 413}) before anything
 * reads it. Designs are kilobytes; a visitor who has not signed in sends a small part of
 * the 5 MB an account may, which the request slots then bound in number (ADR-0108). It
 * runs after Spring Security's chain, which says who is asking, and the body-size limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
class GuestBodyFilter extends OncePerRequestFilter {

	/** The largest body a sandbox's guest sends, in kilobytes of 1,000 bytes. */
	static final int MAX_BODY_KILOBYTES = 256;

	/** The largest body a sandbox's guest sends, in bytes. */
	static final long MAX_BODY_BYTES = MAX_BODY_KILOBYTES * 1_000L;

	private final SecurityContextHolderStrategy contexts;

	private final ErrorResponses responses;

	GuestBodyFilter(ObjectProvider<SecurityContextHolderStrategy> contexts, JsonMapper json) {
		this.contexts = contexts.getIfAvailable(SecurityContextHolder::getContextHolderStrategy);
		this.responses = new ErrorResponses(json);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES && guestAsks()) {
			this.responses.write(response,
					new ApiException(HttpStatus.CONTENT_TOO_LARGE,
							"A sandbox's guest sends at most %d KB at a time: sign in to send more."
								.formatted(MAX_BODY_KILOBYTES),
							List.of()));
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean guestAsks() {
		Authentication authentication = this.contexts.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof BrowserSession session
				&& session.guest();
	}

}
