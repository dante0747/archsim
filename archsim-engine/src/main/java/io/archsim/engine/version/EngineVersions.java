package io.archsim.engine.version;

/**
 * The versions of the engine and of the reports it writes. Until stage 4 of the Java port
 * the runs are simulated by the frozen Python engine (ADR-0017), so these are its
 * versions; the design schema's is the model's
 * ({@code ArchitectureDocument.SCHEMA_VERSION}).
 */
public final class EngineVersions {

	/** The engine every run records. Bump it when what a simulation means changes. */
	public static final String ENGINE = "0.16.0";

	/** The version of the report format. */
	public static final String REPORT = "1.14";

	private EngineVersions() {
	}

}
