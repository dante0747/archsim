package io.archsim.benchmarks.experiments;

/**
 * The measuring protocol's fixed numbers (docs/validation.md, Protocol): how long each
 * load step is measured.
 */
public final class Protocol {

	/** The seconds k6 measures a step. */
	public static final int DURATION_S = 20;

	private Protocol() {
	}

}
