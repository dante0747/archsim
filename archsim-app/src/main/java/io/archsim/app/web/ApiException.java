package io.archsim.app.web;

import java.util.List;

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

	/**
	 * A refusal with issues.
	 * @param status the response's status
	 * @param message what went wrong, for people
	 * @param issues what is wrong with the design, if anything
	 */
	public ApiException(HttpStatus status, String message, List<Issue> issues) {
		super(message);
		this.status = status;
		this.issues = List.copyOf(issues);
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
