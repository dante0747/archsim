package io.archsim.engine.yaml;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Text that is not valid YAML: what PyYAML raised as a {@code YAMLError}, whether its
 * parser could not read the text, its safe constructor refused a value, or one document
 * was expected and the text held several. The message is the problem in the parser's or
 * the constructor's words, with SnakeYAML's excerpt of the text where the parser reports
 * it; {@link #description()} is the problem and where it is, without the text.
 */
public final class InvalidYamlException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * What is wrong and where, as a marked error (PyYAML's {@code MarkedYAMLError}) says
	 * it; {@code null} for an error that marks nothing.
	 */
	private final @Nullable Problem problem;

	/**
	 * Text that is not valid YAML, with no mark of where.
	 * @param message the problem
	 */
	InvalidYamlException(String message) {
		this(message, null, null);
	}

	private InvalidYamlException(String message, @Nullable Problem problem, @Nullable Throwable cause) {
		super(message, cause);
		this.problem = problem;
	}

	/**
	 * The parser's or the composer's report as this exception.
	 * @param ex what SnakeYAML threw
	 * @return the exception, its message SnakeYAML's
	 */
	static InvalidYamlException of(YAMLException ex) {
		Problem problem = (ex instanceof MarkedYAMLException marked)
				? new Problem(marked.getProblem(), Position.of(marked.getProblemMark())) : null;
		return new InvalidYamlException(String.valueOf(ex.getMessage()), problem, ex);
	}

	/**
	 * A value the constructor refuses, as PyYAML's {@code ConstructorError}.
	 * @param context what it was constructing, such as
	 * {@code while constructing a mapping}; {@code null} for nothing
	 * @param problem what is wrong
	 * @param mark where the problem is
	 * @return the exception, its message the context and the problem
	 */
	static InvalidYamlException constructing(@Nullable String context, String problem, Mark mark) {
		String message = (context != null) ? context + ": " + problem : problem;
		return new InvalidYamlException(message, new Problem(problem, Position.of(mark)), null);
	}

	/**
	 * This refusal of a scalar's text, marked where the scalar is, as PyYAML marks it.
	 * @param mark where the scalar starts
	 * @return the marked refusal, this its cause
	 */
	InvalidYamlException at(Mark mark) {
		String message = String.valueOf(getMessage());
		return new InvalidYamlException(message, new Problem(message, Position.of(mark)), this);
	}

	/**
	 * What is wrong and where, as the Python engine's {@code describe_yaml_error} says
	 * it: without the excerpt of the text a message quotes, since the text may have come
	 * from someone else.
	 * @return the problem and its line and column, counted from 1 ({@code ... at line 2,
	 * column 13}); {@code invalid YAML} for a marked error without a problem; the message
	 * for an error that marks nothing
	 */
	public String description() {
		if (this.problem == null) {
			return String.valueOf(getMessage());
		}
		return this.problem.describe();
	}

	/**
	 * What a marked error says is wrong, and where.
	 *
	 * @param what the problem; {@code null} when the error does not say
	 * @param where where it is; {@code null} when the error does not say
	 */
	private record Problem(@Nullable String what, @Nullable Position where) implements Serializable {

		String describe() {
			if (this.what == null) {
				return "invalid YAML";
			}
			return (this.where != null) ? this.what + " at " + this.where : this.what;
		}

	}

	/**
	 * Where a problem is, counted from 1.
	 *
	 * @param line its line
	 * @param column its column
	 */
	private record Position(int line, int column) implements Serializable {

		static @Nullable Position of(@Nullable Mark mark) {
			return (mark != null) ? new Position(mark.getLine() + 1, mark.getColumn() + 1) : null;
		}

		@Override
		public String toString() {
			return "line " + this.line + ", column " + this.column;
		}

	}

}
