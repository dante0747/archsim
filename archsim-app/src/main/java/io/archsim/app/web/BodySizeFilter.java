package io.archsim.app.web;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.ArchsimProperties;

/**
 * Refuses a request body larger than {@code ARCHSIM_MAX_BODY_BYTES} before anything reads
 * it ({@code 413}), and a chunked body, whose size is not declared, on a request that
 * changes something ({@code 411}), with the API's error body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class BodySizeFilter extends OncePerRequestFilter {

	private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH");

	private final long maxBodyBytes;

	private final JsonMapper json;

	BodySizeFilter(ArchsimProperties properties, JsonMapper json) {
		this.maxBodyBytes = properties.maxBodyBytes();
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > this.maxBodyBytes) {
			refuse(response, HttpStatus.CONTENT_TOO_LARGE, "Request body too large");
			return;
		}
		if (request.getContentLengthLong() == -1 && WITH_BODY.contains(request.getMethod()) && isChunked(request)) {
			refuse(response, HttpStatus.LENGTH_REQUIRED, "Content-Length is required");
			return;
		}
		chain.doFilter(request, response);
	}

	private static boolean isChunked(HttpServletRequest request) {
		String encoding = request.getHeader(HttpHeaders.TRANSFER_ENCODING);
		return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
	}

	private void refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		this.json.writeValue(response.getOutputStream(), ErrorBody.of(message, List.of()));
	}

}
