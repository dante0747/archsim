package io.archsim.app.meta;

/**
 * The versions of the engine that simulates the runs. During stage 2 of the Java port the
 * runs are simulated by the frozen Python engine (ADR-0017), so the API reports its
 * versions; a test holds them to the golden files' manifest.
 */
public final class EngineVersions {

	/** The engine every run records. */
	public static final String ENGINE = "0.16.0";

	/** The version of the reports it writes. */
	public static final String REPORT = "1.14";

	private EngineVersions() {
	}

}
