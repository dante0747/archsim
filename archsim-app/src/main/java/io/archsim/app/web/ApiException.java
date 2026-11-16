package io.archsim.app.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import io.archsim.model.issue.Issue;

/**
 * A request the API refuses, answered with the API's error body: its status, a message
 * people read, and the issues that explain it (for a design that cannot be used).
 */
public final class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	private final transient List<Issue> issues;

	private final transient HttpHeaders headers;

	/**
	 * A refusal with issues.
	 * @param status the response's status
	 * @param message what went wrong, for people
	 * @param issues what is wrong with the design, if anything
	 */
	public ApiException(HttpStatus status, String message, List<Issue> issues) {
		this(status, message, issues, HttpHeaders.EMPTY);
	}

	private ApiException(HttpStatus status, String message, List<Issue> issues, HttpHeaders headers) {
		super(message);
		this.status = status;
		this.issues = List.copyOf(issues);
		this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
	}

	/**
	 * A request that needs to know who is asking and does not say: {@code 401} with
	 * {@code WWW-Authenticate: Bearer}, as the Python API answers it.
	 * @param message what to do, such as "Sign in to continue"
	 * @return the refusal
	 */
	public static ApiException unauthorized(String message) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return new ApiException(HttpStatus.UNAUTHORIZED, message, List.of(), headers);
	}

	/**
	 * A request the current state of things does not allow: {@code 409}.
	 * @param message why, and what to do instead
	 * @return the refusal
	 */
	public static ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, message, List.of());
	}

	/**
	 * A request over a rate limit: {@code 429} with {@code Retry-After}.
	 * @param message what was refused and when to try again
	 * @param retryAfterSeconds how many seconds until the limit's window ends
	 * @return the refusal
	 */
	public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message, List.of(), headers);
	}

	/**
	 * A request the server has no room for now, whoever sends it: {@code 503} with
	 * {@code Retry-After}.
	 * @param message what was refused, and what to do instead
	 * @param retryAfterSeconds how many seconds until there may be room
	 * @return the refusal
	 */
	public static ApiException unavailable(String message, long retryAfterSeconds) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message, List.of(), headers);
	}

	/**
	 * Headers the answer carries besides the body's, such as {@code WWW-Authenticate}.
	 * @return the headers, possibly none
	 */
	public HttpHeaders headers() {
		return this.headers;
	}

	/**
	 * The response's status.
	 * @return the status
	 */
	public HttpStatus status() {
		return this.status;
	}

	/**
	 * What is wrong with the design the request carries, if anything.
	 * @return the issues, possibly none
	 */
	public List<Issue> issues() {
		return this.issues;
	}

}
