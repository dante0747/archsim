package io.archsim.app.web;

import java.util.List;
import java.util.Objects;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.issue.Issue;

/**
 * The API's error body, as the web app and API clients read it: {@code {"detail":
 * {"message": "...", "issues": [...]}}}.
 *
 * @param detail what went wrong
 */
public record ErrorBody(Detail detail) {

	/**
	 * The body of a refusal.
	 * @param message what went wrong, for people
	 * @param issues what is wrong with the design, if anything
	 * @return the body
	 */
	public static ErrorBody of(String message, List<Issue> issues) {
		return new ErrorBody(new Detail(message, issues.stream().map(Issue::toJson).toList()));
	}

	/**
	 * The body of a refusal the API answers with.
	 * @param refusal the refusal, whose message is its status's reason when it has none
	 * @return the body
	 */
	public static ErrorBody of(ApiException refusal) {
		String message = Objects.requireNonNullElse(refusal.getMessage(), refusal.status().getReasonPhrase());
		return of(message, refusal.issues());
	}

	/**
	 * What went wrong.
	 *
	 * @param message what went wrong, for people
	 * @param issues each issue as the design's validation writes it
	 */
	public record Detail(String message, List<ObjectNode> issues) {

	}

}
