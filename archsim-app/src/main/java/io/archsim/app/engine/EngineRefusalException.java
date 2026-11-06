package io.archsim.app.engine;

import java.util.List;

import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;
import io.archsim.model.issue.Issue;

/**
 * The engine service refused a call: it could not read the design (its issues say why),
 * or what the request carries (text, a mapping, telemetry) cannot be used.
 */
public final class EngineRefusalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String engineMessage;

	private final transient List<Issue> issues;

	private final Kind kind;

	private EngineRefusalException(String engineMessage, List<Issue> issues, Kind kind) {
		super("The engine refused: " + engineMessage);
		this.engineMessage = engineMessage;
		this.issues = List.copyOf(issues);
		this.kind = kind;
	}

	/**
	 * A design the engine cannot read ({@code DocumentInvalidError}).
	 * @param message what the engine said
	 * @param issues why it cannot read the design
	 * @return the refusal
	 */
	public static EngineRefusalException invalidDesign(String message, List<Issue> issues) {
		return new EngineRefusalException(message, issues, Kind.INVALID);
	}

	/**
	 * Text, a mapping or telemetry that cannot be used: its message is the API's answer.
	 * @param message what is wrong, in the words the Python API answered
	 * @return the refusal
	 */
	public static EngineRefusalException unreadable(String message) {
		return new EngineRefusalException(message, List.of(), Kind.UNREADABLE);
	}

	/**
	 * What the engine said.
	 * @return its words
	 */
	public String engineMessage() {
		return this.engineMessage;
	}

	/**
	 * Why the engine could not read the design.
	 * @return the engine's issues, none for a run over its limits
	 */
	public List<Issue> issues() {
		return this.issues;
	}

	/**
	 * The refusal as the API answers it: in the engine service's words for what it cannot
	 * use, else with the design's issues under the call's own words.
	 * @param invalid what the API says of a design the engine cannot read, such as
	 * {@code The imported design is not valid}
	 * @return the API's {@code 422}
	 */
	public ApiException answer(String invalid) {
		if (this.kind == Kind.UNREADABLE) {
			return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, this.engineMessage, List.of());
		}
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, invalid, this.issues);
	}

	/**
	 * Why the engine refused, as the engine service's {@code kind} says.
	 */
	private enum Kind {

		/** A design it cannot read. */
		INVALID,

		/** Text, a mapping or telemetry that cannot be used. */
		UNREADABLE

	}

}
