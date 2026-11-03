package io.archsim.app.web;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import io.archsim.app.config.ArchsimProperties;

/**
 * Headers that keep API answers from being framed, sniffed, cached or leaked, on every
 * response, as the Python API sets them. A handler that sets one of them itself wins. The
 * interactive docs load their scripts, so they keep the browser's defaults for content.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityHeadersFilter extends OncePerRequestFilter {

	private static final List<String> PRIVATE = List.of("auth", "tokens", "workspaces", "invitations")
		.stream()
		.map((prefix) -> WebConfiguration.PREFIX + "/" + prefix)
		.toList();

	private static final List<String> DOCS = List.of(WebConfiguration.PREFIX + "/docs",
			WebConfiguration.PREFIX + "/swagger-ui");

	/** Reads the decoded path, as Starlette matches its prefixes on it. */
	private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

	private final boolean strictTransport;

	SecurityHeadersFilter(ArchsimProperties properties) {
		this.strictTransport = properties.servedOverHttps();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = PATHS.getPathWithinApplication(request);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
		response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
		if (DOCS.stream().noneMatch(path::startsWith)) {
			response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
		}
		if (PRIVATE.stream().anyMatch(path::startsWith)) {
			response.setHeader("Cache-Control", "no-store");
		}
		if (this.strictTransport) {
			response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		}
		chain.doFilter(request, response);
	}

}
