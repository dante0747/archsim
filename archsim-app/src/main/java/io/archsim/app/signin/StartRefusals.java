package io.archsim.app.signin;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.ErrorResponses;
import io.archsim.app.web.RequestInvalidException;

/**
 * Answers a sign-in that {@link SignInStarts} refused to start ({@code 422} for its
 * {@code next}, {@code 429} for too many) as the API answers a refusal, where Spring
 * Security's redirect filter would answer {@code 500}.
 */
final class StartRefusals implements AuthenticationFailureHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(StartRefusals.class);

	private final ErrorResponses responses;

	StartRefusals(ErrorResponses responses) {
		this.responses = responses;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		switch (exception.getCause()) {
			case ApiException refusal -> this.responses.write(response, refusal);
			case RequestInvalidException invalid -> this.responses.write(response, invalid);
			case null, default -> {
				LOGGER.error("Sign-in could not start", exception);
				response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
			}
		}
	}

}
