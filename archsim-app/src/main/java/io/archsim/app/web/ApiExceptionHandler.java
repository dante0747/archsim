package io.archsim.app.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.node.ArrayNode;

/**
 * Turns what the API refuses into the error bodies today's clients read (ADR-0034): a
 * domain refusal as {@code {"detail": {"message", "issues"}}}, a request body that does
 * not fit as FastAPI's list of pydantic errors, and what Spring refuses on its own (no
 * such route, a method the route does not take, ...) with its status and headers and the
 * body Starlette gives it, such as {@code {"detail": "Not Found"}}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler
	ResponseEntity<ErrorBody> refused(ApiException ex) {
		String message = Objects.requireNonNullElse(ex.getMessage(), ex.status().getReasonPhrase());
		return ResponseEntity.status(ex.status()).headers(ex.headers()).body(ErrorBody.of(message, ex.issues()));
	}

	@ExceptionHandler
	ResponseEntity<Map<String, ArrayNode>> requestInvalid(RequestInvalidException ex) {
		return ResponseEntity.unprocessableContent().body(Map.of("detail", ex.detail()));
	}

	/**
	 * A failure no handler expects: a bug, logged and answered as Starlette answers one.
	 * @param ex what failed
	 * @return {@code 500 Internal Server Error}, as plain text
	 */
	@ExceptionHandler
	ResponseEntity<Object> failed(Exception ex) {
		LOGGER.error("Request failed", ex);
		return StatusAnswers.failure();
	}

	/**
	 * A method the route does not take, with the methods it does: Starlette names
	 * {@code HEAD} wherever it names {@code GET}, as Spring serves it there too.
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
			HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Set<HttpMethod> supported = Objects.requireNonNullElse(ex.getSupportedHttpMethods(), Set.of());
		List<String> allowed = new ArrayList<>(supported.stream().map(HttpMethod::name).toList());
		if (supported.contains(HttpMethod.GET) && !supported.contains(HttpMethod.HEAD)) {
			allowed.add(allowed.indexOf(HttpMethod.GET.name()) + 1, HttpMethod.HEAD.name());
		}
		HttpHeaders answered = new HttpHeaders();
		answered.addAll(headers);
		answered.set(HttpHeaders.ALLOW, String.join(", ", allowed));
		return handleExceptionInternal(ex, null, answered, status, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		if (statusCode.is5xxServerError()) {
			LOGGER.error("Request failed", ex);
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		return StatusAnswers.of(statusCode, headers);
	}

}
