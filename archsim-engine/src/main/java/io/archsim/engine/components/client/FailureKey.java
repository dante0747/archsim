package io.archsim.engine.components.client;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Outcome;
import io.archsim.model.text.PythonText;

/**
 * How a group of failed user requests failed, as the report's failure breakdown lists
 * them.
 *
 * @param status how they ended
 * @param reason why; {@code null} when the failure gave none
 * @param nodeId where the failure started
 * @param edgeId the link a caller gave up on
 */
public record FailureKey(Outcome.Status status, @Nullable FailureReason reason, @Nullable String nodeId,
		@Nullable String edgeId) {

	/** The reason the report gives a failure that has none. */
	public static final String UNKNOWN = "unknown";

	/**
	 * The key of a failure.
	 * @param outcome how a request failed
	 * @return its key
	 */
	public static FailureKey of(Outcome outcome) {
		return new FailureKey(outcome.status(), outcome.reason(), outcome.nodeId(), outcome.edgeId());
	}

	/**
	 * The reason as the report names it.
	 * @return such as {@code queue_full}, or {@code unknown}
	 */
	public String reasonName() {
		return (this.reason != null) ? this.reason.json() : UNKNOWN;
	}

	/**
	 * The key as Python writes the tuple {@code (status, reason, nodeId, edgeId)}, which
	 * orders failures of equal counts in the report.
	 * @return such as {@code ('rejected', 'queue_full', 'svc', None)}
	 */
	public String pythonTuple() {
		return "(" + PythonText.repr(this.status.json()) + ", " + PythonText.repr(reasonName()) + ", "
				+ optional(this.nodeId) + ", " + optional(this.edgeId) + ")";
	}

	private static String optional(@Nullable String text) {
		return (text != null) ? PythonText.repr(text) : "None";
	}

}
