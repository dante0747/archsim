package io.archsim.app.engine;

import java.util.List;

import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;
import io.archsim.model.issue.Issue;

/**
 * The engine service refused a design it cannot read; its issues say why.
 */
public final class EngineRefusalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient List<Issue> issues;

	private EngineRefusalException(String engineMessage, List<Issue> issues) {
		super("The engine refused: " + engineMessage);
		this.issues = List.copyOf(issues);
	}

	/**
	 * A design the engine cannot read ({@code DocumentInvalidError}).
	 * @param message what the engine said
	 * @param issues why it cannot read the design
	 * @return the refusal
	 */
	public static EngineRefusalException invalidDesign(String message, List<Issue> issues) {
		return new EngineRefusalException(message, issues);
	}

	/**
	 * Why the engine could not read the design.
	 * @return the engine's issues
	 */
	public List<Issue> issues() {
		return this.issues;
	}

	/**
	 * The refusal as the API answers it: the design's issues under the call's own words.
	 * @param invalid what the API says of a design the engine cannot read, such as
	 * {@code The document cannot be calibrated}
	 * @return the API's {@code 422}
	 */
	public ApiException answer(String invalid) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, invalid, this.issues);
	}

}
