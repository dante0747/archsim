package io.archsim.engine.calibration.mapping;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.RecordCodec;

/**
 * The range of time a calibration reads, in ISO 8601.
 *
 * @param start when it starts
 * @param end when it ends
 * @param step the seconds between points
 */
public record TimeRange(String start, String end, @Default("60") @Gt(0) @Le(86_400) double step) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * A range written {@code START/END}, as the Python engine's {@code parse_range} reads
	 * it: split at the first slash, the times kept as written.
	 * @param text the range
	 * @param step the seconds between points
	 * @return the range
	 * @throws IllegalArgumentException with the Python engine's words when nothing
	 * follows a slash
	 * @throws BindingException when the step is not above 0 and at most a day
	 */
	public static TimeRange parse(String text, double step) {
		int slash = text.indexOf('/');
		if (slash < 0 || slash == text.length() - 1) {
			throw new IllegalArgumentException("a range is START/END, e.g. 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z");
		}
		ObjectNode range = NODES.objectNode()
			.put("start", text.substring(0, slash))
			.put("end", text.substring(slash + 1))
			.put("step", step);
		return RecordCodec.read(range, TimeRange.class);
	}

}
