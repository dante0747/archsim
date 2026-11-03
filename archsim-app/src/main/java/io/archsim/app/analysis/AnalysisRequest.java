package io.archsim.app.analysis;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * What {@code POST /validate} and {@code POST /analyze} take: a design, and the scenario
 * of it to look at, if any.
 *
 * @param document the design, as the editor holds it
 * @param scenarioId the scenario's id, or {@code null} (or empty) for the design itself
 */
record AnalysisRequest(ObjectNode document, @Nullable String scenarioId) {

	/**
	 * The scenario the request names: an empty id names none, as in the Python API.
	 * @return the scenario's id, if any
	 */
	Optional<String> scenario() {
		return Optional.ofNullable(this.scenarioId).filter((id) -> !id.isEmpty());
	}

}
