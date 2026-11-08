package io.archsim.engine.runtime;

import org.jspecify.annotations.Nullable;

/**
 * A step a traced request took.
 *
 * @param nodeId the part, if the step was at one
 * @param edgeId the link, if the step was on one
 * @param kind what the step was
 * @param start when it started, in simulated milliseconds
 * @param end when it ended
 * @param depth the call depth: 0 at the client, one more for each link crossed
 * @param detail what to say about it
 */
public record Span(@Nullable String nodeId, @Nullable String edgeId, SpanKind kind, double start, double end, int depth,
		@Nullable String detail) {

}
