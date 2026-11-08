package io.archsim.engine.version;

/**
 * The versions of the engine and of the reports it writes. Until stage 4 of the Java port
 * ends, the runs are simulated by the frozen Python engine (ADR-0017), so the engine's is
 * still its version; the report's is the Java simulator's own (ADR-0050). The design
 * schema's is the model's ({@code ArchitectureDocument.SCHEMA_VERSION}).
 */
public final class EngineVersions {

	/** The engine every run records. Bump it when what a simulation means changes. */
	public static final String ENGINE = "0.16.0";

	/**
	 * The version of the report format the Java simulator writes: 1.15 names the Java
	 * runtime in the provenance ({@code java}) where 1.14, the Python engine's, named
	 * {@code python} and {@code simpy}.
	 */
	public static final String REPORT = "1.15";

	private EngineVersions() {
	}

}
