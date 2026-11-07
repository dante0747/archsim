package io.archsim.engine.calibration.otlp;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One span of an OpenTelemetry trace.
 *
 * @param traceId its trace's id
 * @param spanId its id
 * @param parentId its parent's id; {@code null}, or empty, for a root
 * @param service the service that recorded it ({@code service.name})
 * @param name its name
 * @param kind its kind: {@code SERVER}, {@code CLIENT}, {@code PRODUCER},
 * {@code CONSUMER} or {@code INTERNAL}, or the kind as written when it is none of them
 * @param startNs when it started, in nanoseconds since the epoch
 * @param endNs when it ended, in nanoseconds since the epoch
 * @param attrs its attributes' values by key, {@code null} for a value of no known type
 */
public record Span(String traceId, String spanId, @Nullable String parentId, String service, String name, String kind,
		long startNs, long endNs, ObjectNode attrs) {

	private static final double NS_PER_MS = 1e6;

	/**
	 * Copies the attributes; an empty parent id is none, as Python's truthiness has it.
	 */
	public Span {
		parentId = (parentId == null || parentId.isEmpty()) ? null : parentId;
		attrs = attrs.deepCopy();
	}

	/**
	 * The attributes, as a copy: a record does not change.
	 * @return a copy of the attributes
	 */
	@Override
	public ObjectNode attrs() {
		return this.attrs.deepCopy();
	}

	/**
	 * One attribute's value.
	 * @param key the attribute's key
	 * @return a copy of its value; a missing node when the span has no such attribute
	 */
	public JsonNode attr(String key) {
		return this.attrs.path(key).deepCopy();
	}

	/**
	 * How long it took.
	 * @return its duration in milliseconds; 0 when it ends before it starts
	 */
	public double ms() {
		return Math.max(0, this.endNs - this.startNs) / NS_PER_MS;
	}

}
