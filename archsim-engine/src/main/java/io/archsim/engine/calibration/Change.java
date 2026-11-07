package io.archsim.engine.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One input a calibration set.
 *
 * @param path where the provenance records it, such as {@code nodes/api/serviceTimeMs}
 * @param label the input in words, such as {@code API Servers: Service time}
 * @param before its value before; JSON {@code null} when it had none
 * @param after the value it was set to
 * @param fitError the fit's error; {@code null} when nothing was fitted or there is
 * nothing to check it against
 * @param query what it was read from; {@code null} for nothing
 */
public record Change(String path, String label, JsonNode before, JsonNode after, @Nullable Double fitError,
		@Nullable String query) {

	/**
	 * Copies the values.
	 */
	public Change {
		before = before.deepCopy();
		after = after.deepCopy();
	}

	@Override
	public JsonNode before() {
		return this.before.deepCopy();
	}

	@Override
	public JsonNode after() {
		return this.after.deepCopy();
	}

	/**
	 * The change as the Python engine's {@code Change.to_dict} writes it.
	 * @return {@code {path, label, before, after, fitError, query}}
	 */
	public ObjectNode toJson() {
		ObjectNode json = JsonNodeFactory.instance.objectNode().put("path", this.path).put("label", this.label);
		json.set("before", before());
		json.set("after", after());
		json.put("fitError", this.fitError);
		return json.put("query", this.query);
	}

}
