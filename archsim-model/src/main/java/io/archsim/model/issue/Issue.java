package io.archsim.model.issue;

import io.archsim.model.codec.RecordCodec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Something wrong with a design, or worth knowing about it: a stable code, a message for
 * people (word for word the Python engine's, because the editor shows it), what it
 * concerns, and how to fix it (ADR-0024).
 *
 * <p>
 * Build one with {@link #error} or {@link #warning}, then say where it is:
 * {@code Issue.error("edge.self_loop", message).atEdge(id).withHint(hint)}.
 *
 * @param severity whether it stops a simulation
 * @param code a stable identifier, such as {@code edge.unknown_node}
 * @param message what is wrong, for people
 * @param nodeId the node it concerns
 * @param edgeId the edge it concerns
 * @param path where in the document or the node's config it is
 * @param hint how to fix it
 */
public record Issue(Severity severity, String code, String message, @Nullable String nodeId, @Nullable String edgeId,
		@Nullable String path, @Nullable String hint) {

	public Issue {
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(message, "message");
	}

	/** An issue that stops a simulation. */
	public static Issue error(String code, String message) {
		return new Issue(Severity.ERROR, code, message, null, null, null, null);
	}

	/** An issue worth knowing about that does not stop a simulation. */
	public static Issue warning(String code, String message) {
		return new Issue(Severity.WARNING, code, message, null, null, null, null);
	}

	public Issue atNode(@Nullable String id) {
		return new Issue(severity, code, message, id, edgeId, path, hint);
	}

	public Issue atEdge(@Nullable String id) {
		return new Issue(severity, code, message, nodeId, id, path, hint);
	}

	public Issue atPath(@Nullable String where) {
		return new Issue(severity, code, message, nodeId, edgeId, where, hint);
	}

	public Issue withHint(@Nullable String text) {
		return new Issue(severity, code, message, nodeId, edgeId, path, text);
	}

	public boolean isError() {
		return severity == Severity.ERROR;
	}

	/**
	 * The issue as the API and the golden files write it: every field, {@code null}
	 * included.
	 */
	public ObjectNode toJson() {
		return RecordCodec.write(this);
	}
}
