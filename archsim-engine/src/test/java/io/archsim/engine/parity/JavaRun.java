package io.archsim.engine.parity;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * A Java run of a template case's seed, as the parity run judges it.
 *
 * @param figures what the baselines keep of it, read as the frozen engine's
 * ({@link RunFigures}), or the error it failed with ({@link RunError})
 * @param departures the departures it shows that the baselines keep too little of to read
 * as the frozen engine's ({@link RunDepartures#unread}); none for a run that failed
 */
record JavaRun(JsonNode figures, Set<String> departures) {

	/**
	 * Copies the departures, so the record does not change.
	 */
	JavaRun {
		departures = Set.copyOf(departures);
	}

	/**
	 * A run that failed.
	 * @param failure what it failed with
	 * @return the run, its figures its error
	 */
	static JavaRun failed(Throwable failure) {
		return new JavaRun(RunError.of(failure), Set.of());
	}

}
