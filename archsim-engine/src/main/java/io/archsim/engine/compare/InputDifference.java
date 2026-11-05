package io.archsim.engine.compare;

import tools.jackson.databind.JsonNode;

/**
 * One setting two simulated designs differ in.
 *
 * @param path where the setting is, such as {@code node[api].replicas} or
 * {@code workload.faults[0].durationS}
 * @param label the setting in words, such as {@code API: replicas}
 * @param a its value in the first design, as the report stores it; JSON {@code null} when
 * that design does not have it
 * @param b its value in the second design, as the report stores it; JSON {@code null}
 * when that design does not have it
 */
public record InputDifference(String path, String label, JsonNode a, JsonNode b) {

	/**
	 * Copies both values, so the record does not change.
	 * @param path where the setting is
	 * @param label the setting in words
	 * @param a its value in the first design
	 * @param b its value in the second design
	 */
	public InputDifference {
		a = a.deepCopy();
		b = b.deepCopy();
	}

	/**
	 * The setting's value in the first design, as a copy: a record does not change.
	 * @return a copy of the value
	 */
	@Override
	public JsonNode a() {
		return this.a.deepCopy();
	}

	/**
	 * The setting's value in the second design, as a copy: a record does not change.
	 * @return a copy of the value
	 */
	@Override
	public JsonNode b() {
		return this.b.deepCopy();
	}

}
