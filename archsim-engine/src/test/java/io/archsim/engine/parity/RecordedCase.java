package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * A template case's runs, as the simulation baselines recorded them.
 *
 * @param template the template
 * @param scenario the scenario, or {@code base} for the design as drawn
 * @param runs each seed's run: its seed and figures, or the error it ended in
 */
record RecordedCase(String template, String scenario, List<JsonNode> runs) {

	/**
	 * Copies the runs, so the record does not change.
	 */
	RecordedCase {
		runs = List.copyOf(runs);
	}

}
