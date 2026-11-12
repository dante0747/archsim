package io.archsim.app.access;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.ErrorResponses;

/**
 * Answers a request whose API token is refused, wherever it was sent: {@code 401} with
 * {@code WWW-Authenticate: Bearer} and the API's error body saying why, as the web app
 * and API clients read it. A token that is not one of ours, or a {@code Bearer} header
 * Spring Security cannot read, is told how to send one.
 */
public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ErrorResponses responses;

	/**
	 * An entry point that writes its refusals as the API writes its errors.
	 * @param responses writes a refusal outside Spring MVC
	 */
	public ApiAuthenticationEntryPoint(ErrorResponses responses) {
		this.responses = responses;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		this.responses.write(response, ApiException.unauthorized(message(ex)));
	}

	/**
	 * What the token's holder is told.
	 * @param ex why the bearer token filter refused the request
	 * @return why the token was refused, or, for a header that holds no bearer token
	 * Spring Security can read, how to send one
	 */
	private static String message(AuthenticationException ex) {
		if (ex instanceof InvalidBearerTokenException) {
			return Objects.requireNonNullElse(ex.getMessage(), SignInRefusals.TOKEN_NOT_VALID);
		}
		return SignInRefusals.SEND_A_TOKEN;
	}

}
