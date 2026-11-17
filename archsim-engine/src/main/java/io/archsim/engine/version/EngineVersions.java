package io.archsim.engine.version;

/**
 * The versions of the engine and of the reports it writes. The engine is the Java
 * simulator, which every run has simulated since stage 4 of the Java port: 1.0.0 replays
 * the frozen Python engine 0.16.0 run for run (ADR-0049), and runs stored before it name
 * 0.16.0; 1.1.0 bills an autoscaled part at the count it settles at under each load
 * (ADR-0074); 1.1.1 measures the base's per-request rates in the buckets nearest the base
 * load when none is at it; 1.2.0 prices a third-party API without a price at its
 * technology preset's price, or leaves it out of the month and says so (ADR-0081); 1.2.1
 * counts the lookups a cache refused in its hit ratio, and asks after a cache's outage
 * only for the fallback its callers lack. The report's version is the Java simulator's
 * own too (ADR-0050). The design schema's is the model's
 * ({@code ArchitectureDocument.SCHEMA_VERSION}).
 */
public final class EngineVersions {

	/** The engine every run records. Bump it when what a simulation means changes. */
	public static final String ENGINE = "1.2.1";

	/**
	 * The version of the report format the Java simulator writes: 1.17 adds the parts the
	 * cost leaves out for want of a price ({@code unpriced}, ADR-0081); 1.16 adds the
	 * cost's hour at the base load and at the peak ({@code hourly}) and each cost line's
	 * instances at the peak ({@code peakInstances}, ADR-0074); 1.15 named the Java
	 * runtime in the provenance ({@code java}) where 1.14, the Python engine's, named
	 * {@code python} and {@code simpy}.
	 */
	public static final String REPORT = "1.17";

	private EngineVersions() {
	}

}
