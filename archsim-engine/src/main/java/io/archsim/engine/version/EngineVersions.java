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
 * only for the fallback its callers lack (ADR-0082); 1.3.0 reports a database's busiest
 * instance's CPU and sets its CPU ceiling where that instance fills (ADR-0083); 1.4.0
 * counts a design's lost messages against its availability and judges how old its
 * messages are when processed (ADR-0084); 1.5.0 sizes a Kafka broker from what ArchSim's
 * Kafka experiment measured and reports a queue's idle consumers (ADR-0085); 1.5.1 counts
 * the log flush a database's write waits for as a wait in the instant estimate every
 * report records, not as work on its cores (ADR-0089); 1.5.2 spreads a cache's commands
 * over every shard's cores in that estimate, and counts a read-through cache's fill of a
 * key it missed in its slots and CPU (ADR-0092); 1.6.0 counts the vCPUs of a service,
 * database or cache with 2 or more vCPU as hyperthread pairs at a sibling speed of 0.6 by
 * default from schema 1.11, as whole cores on a whole-core instance family, a serverless
 * platform or a pod, sizes a part for the kind its instance type gives it, and saturates
 * a part on its own hyperthread pairs in the instant estimate where its vCPUs fill at the
 * sibling speed, and judges its CPU in the design review by the same ceiling (ADR-0091);
 * 1.6.1 places evenly spaced arrivals where the offered rate has added up to one more
 * request, so a ramp or a trace that starts at 0 sends its requests from the start; 1.6.2
 * counts a queue's deliveries alone in its holding time, so its capacity, its formulas
 * and its finding on what it can sustain follow from how long a message holds a consumer
 * (ADR-0117). The report's version is the Java simulator's own too (ADR-0050). The design
 * schema's is the model's ({@code ArchitectureDocument.SCHEMA_VERSION}).
 */
public final class EngineVersions {

	/** The engine every run records. Bump it when what a simulation means changes. */
	public static final String ENGINE = "1.6.2";

	/**
	 * The version of the report format the Java simulator writes: 1.21 adds a sized
	 * part's {@code vcpus}, how its vCPUs counted, and the model notes that name the
	 * parts on the default sibling speed and the pods (ADR-0091); 1.20 adds a queue's
	 * {@code idleConsumers} (ADR-0085); 1.19 adds, for a design with a queue, the
	 * summary's {@code messageAgeP95Ms}, {@code lostMessages} and {@code availability}
	 * (ADR-0084); 1.18 adds a database's busiest instance's share of its cores busy
	 * ({@code busiestInstanceCpuUtilization}, ADR-0083); 1.17 adds the parts the cost
	 * leaves out for want of a price ({@code unpriced}, ADR-0081); 1.16 adds the cost's
	 * hour at the base load and at the peak ({@code hourly}) and each cost line's
	 * instances at the peak ({@code peakInstances}, ADR-0074); 1.15 named the Java
	 * runtime in the provenance ({@code java}) where 1.14, the Python engine's, named
	 * {@code python} and {@code simpy}.
	 */
	public static final String REPORT = "1.21";

	private EngineVersions() {
	}

}
