package io.archsim.app.access;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.ErrorResponses;

/**
 * Answers a change a browser session authenticates that does not send the session's CSRF
 * token: {@code 403} with the API's error body, saying what to send. It is the chain's
 * only refusal of this kind: every request is authorized.
 */
public final class CsrfRefusal implements AccessDeniedHandler {

	/** What the browser is told. */
	private static final String MESSAGE = "Missing or wrong " + BrowserCsrf.HEADER + " header: send the csrfToken from "
			+ "GET /auth/me (or the " + BrowserCsrf.COOKIE + " cookie) with every change";

	private final ErrorResponses responses;

	/**
	 * A handler that writes its refusal as the API writes its errors.
	 * @param responses writes a refusal outside Spring MVC
	 */
	public CsrfRefusal(ErrorResponses responses) {
		this.responses = responses;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		this.responses.write(response, new ApiException(HttpStatus.FORBIDDEN, MESSAGE, List.of()));
	}

}
