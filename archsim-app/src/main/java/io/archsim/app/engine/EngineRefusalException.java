package io.archsim.app.engine;

import java.util.List;

import io.archsim.model.issue.Issue;

/**
 * The engine could not read the design it was given; its issues say why.
 */
public final class EngineRefusalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient List<Issue> issues;

	/**
	 * A refusal and its reasons.
	 * @param issues why the engine could not read the design
	 */
	public EngineRefusalException(List<Issue> issues) {
		super("The engine cannot read the design: " + issues.size() + " issues");
		this.issues = List.copyOf(issues);
	}

	/**
	 * Why the engine could not read the design.
	 * @return the engine's issues
	 */
	public List<Issue> issues() {
		return this.issues;
	}

}
