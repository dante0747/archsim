package io.archsim.app.engine;

import java.util.List;

import io.archsim.model.issue.Issue;

/**
 * The engine refused a call: it could not read the design (its issues say why), or the
 * run is over its limits.
 */
public final class EngineRefusalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String engineMessage;

	private final transient List<Issue> issues;

	private final boolean overLimits;

	private EngineRefusalException(String engineMessage, List<Issue> issues, boolean overLimits) {
		super("The engine refused: " + engineMessage);
		this.engineMessage = engineMessage;
		this.issues = List.copyOf(issues);
		this.overLimits = overLimits;
	}

	/**
	 * A design the engine cannot read ({@code DocumentInvalidError}).
	 * @param message what the engine said
	 * @param issues why it cannot read the design
	 * @return the refusal
	 */
	public static EngineRefusalException invalidDesign(String message, List<Issue> issues) {
		return new EngineRefusalException(message, issues, false);
	}

	/**
	 * A run over its limits ({@code SimulationLimitError}).
	 * @param message what the engine said
	 * @return the refusal
	 */
	public static EngineRefusalException runOverLimits(String message) {
		return new EngineRefusalException(message, List.of(), true);
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
	 * Whether the run is over its limits rather than a design the engine cannot read.
	 * @return {@code true} for a run over its limits
	 */
	public boolean overLimits() {
		return this.overLimits;
	}

}
