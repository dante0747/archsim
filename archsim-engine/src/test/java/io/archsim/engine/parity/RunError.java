package io.archsim.engine.parity;

import java.util.Objects;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * A failed run as the since-deleted {@code scripts/export_simulation_baselines.py}
 * recorded one: {@code {"error": "<type>: <message>"}}, with the frozen engine's name for
 * the engine's own refusals ({@code SimulationLimitError}, {@code DocumentInvalidError})
 * and the Java type's for anything else, which no Python run gave.
 */
final class RunError {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private RunError() {
	}

	/**
	 * The run's error.
	 * @param failure what the run failed with
	 * @return its row
	 */
	static ObjectNode of(Throwable failure) {
		return JSON.createObjectNode()
			.put("error", pythonType(failure) + ": " + Objects.requireNonNullElse(failure.getMessage(), ""));
	}

	private static String pythonType(Throwable failure) {
		return switch (failure) {
			case SimulationLimitException _ -> "SimulationLimitError";
			case DocumentInvalidException _ -> "DocumentInvalidError";
			default -> failure.getClass().getSimpleName();
		};
	}

}
