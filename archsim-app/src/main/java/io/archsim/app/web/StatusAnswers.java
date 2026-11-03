package io.archsim.app.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The answers Starlette gives a request it refuses before any endpoint runs: a status's
 * reason as {@code {"detail": "Not Found"}}, and for a failure the plain text
 * {@code Internal Server Error}.
 */
final class StatusAnswers {

	private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

	private StatusAnswers() {
	}

	/**
	 * The answer for a status.
	 * @param status the status to answer with
	 * @param headers headers the answer keeps, such as {@code Allow} on a {@code 405}
	 * @return the answer
	 */
	static ResponseEntity<Object> of(HttpStatusCode status, HttpHeaders headers) {
		if (status.isSameCodeAs(HttpStatus.INTERNAL_SERVER_ERROR)) {
			return failure();
		}
		HttpStatus known = HttpStatus.resolve(status.value());
		String reason = (known != null) ? known.getReasonPhrase() : Integer.toString(status.value());
		return ResponseEntity.status(status)
			.headers(headers)
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("detail", reason));
	}

	/**
	 * The answer for a failure: a bug, logged where it happened.
	 * @return {@code 500} with Starlette's plain text
	 */
	static ResponseEntity<Object> failure() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.contentType(MediaType.TEXT_PLAIN)
			.body(INTERNAL_SERVER_ERROR);
	}

}
