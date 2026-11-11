package io.archsim.mcp;

import java.io.Serial;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;

/**
 * A call a tool refuses, in the Python server's words, which the agent sees as the
 * result's text: an unusable document, an argument it cannot use, a file it cannot read.
 */
final class ToolRefusal extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A call refused.
	 * @param message why, as the Python server said it
	 */
	ToolRefusal(String message) {
		super(Objects.requireNonNull(message, "message"));
	}

	/**
	 * A call refused for what the engine raised.
	 * @param message why, as the Python server said it
	 * @param cause what the engine raised
	 */
	ToolRefusal(String message, Throwable cause) {
		super(Objects.requireNonNull(message, "message"), cause);
	}

	/**
	 * A document's issues as the Python server's {@code _fix} refused it with them: one
	 * line each, its severity, its code, the part or link it is about, its message and
	 * how to fix it, an empty one left out as Python left it out ({@code node_id or
	 * edge_id}, {@code if hint}).
	 * @param ex the document's refusal
	 * @return the tool's refusal
	 */
	static ToolRefusal unusable(DocumentInvalidException ex) {
		StringBuilder message = new StringBuilder("The document cannot be used:");
		for (Issue issue : ex.issues()) {
			String about = given(issue.nodeId()) ? issue.nodeId() : issue.edgeId();
			message.append('\n')
				.append(issue.severity().json())
				.append(' ')
				.append(issue.code())
				.append((about != null && !about.isEmpty()) ? ' ' + bracketed(about) : "")
				.append(": ")
				.append(issue.message())
				.append(given(issue.hint()) ? " Fix: " + issue.hint() : "");
		}
		return new ToolRefusal(message.toString(), ex);
	}

	/**
	 * Why the call was refused.
	 * @return the message, never {@code null}
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

	private static String bracketed(String about) {
		return '[' + about + ']';
	}

	// as Python tested a text by its truth
	private static boolean given(@Nullable String text) {
		return text != null && !text.isEmpty();
	}

}
