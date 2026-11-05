package io.archsim.engine.estimate;

/**
 * One part's estimate: its utilization and wait when the formulas have them, else why
 * not.
 */
public sealed interface PartEstimate permits EstimatedPart, UnestimatedPart {

	/**
	 * The part's id.
	 * @return the id
	 */
	String id();

	/**
	 * Whether the part is on a client's request path.
	 * @return {@code true} when a client's requests reach it
	 */
	boolean onPath();

	/**
	 * How the part fares at the peak.
	 * @return its status
	 */
	PartStatus status();

}
