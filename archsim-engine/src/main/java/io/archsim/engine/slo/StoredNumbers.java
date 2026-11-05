package io.archsim.engine.slo;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The numbers stored with a run, read as the Python engine's {@code get} reads them: a
 * value the JSON leaves out or writes as {@code null} is none.
 */
final class StoredNumbers {

	private StoredNumbers() {
	}

	/**
	 * A number of a stored object.
	 * @param json the object
	 * @param name the number's key
	 * @return the number, or {@code null} when the object has no number there
	 */
	static @Nullable Double number(JsonNode json, String name) {
		JsonNode value = json.path(name);
		return value.isNumber() ? value.doubleValue() : null;
	}

}
