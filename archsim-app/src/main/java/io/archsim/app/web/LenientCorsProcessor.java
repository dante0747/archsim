package io.archsim.app.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * CORS as Starlette's middleware does it: a request from an origin that is not allowed is
 * served without CORS headers, so the browser keeps the answer from the page, instead of
 * being refused with {@code 403}. Requests that reach the API through the web server
 * carry the site's {@code Origin} whether or not it is listed, and must keep working.
 * Preflight requests are checked as Spring checks them.
 */
class LenientCorsProcessor extends DefaultCorsProcessor {

	@Override
	public boolean processRequest(@Nullable CorsConfiguration config, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (config != null && !CorsUtils.isPreFlightRequest(request)
				&& config.checkOrigin(request.getHeader(HttpHeaders.ORIGIN)) == null) {
			response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
			return true;
		}
		return super.processRequest(config, request, response);
	}

}
