package io.archsim.parity;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One recorded call of a Python engine function: its inputs, references resolved, and
 * either what it returned or what it raised.
 *
 * @param file the golden file it is in, such as {@code fixtures/migrations}
 * @param pointer where it is in the file, as a JSON Pointer
 * @param fn the Python function, such as {@code archsim_engine.scenarios.load_document}
 * @param input its parameters by name, in camelCase, references resolved
 * @param output what it returned, or {@code null} when it raised
 * @param error what it raised, {@code {type, message}} and maybe {@code issues}, or
 * {@code null}
 */
public record GoldenEntry(String file, String pointer, String fn, ObjectNode input, @Nullable JsonNode output,
		@Nullable JsonNode error) {

	public GoldenEntry {
		if ((output == null) == (error == null)) {
			throw new IllegalArgumentException(pointer + ": an entry has an output or an error");
		}
	}

	/**
	 * The parameter {@code name}: as recorded, but a document, or a report's design, at
	 * the current schema ({@link SchemaDepartures#asGiven}).
	 */
	public JsonNode input(String name) {
		return SchemaDepartures.asGiven(name,
				Objects.requireNonNull(this.input.get(name), () -> this.pointer + " has no input " + name));
	}

	public boolean raised() {
		return this.error != null;
	}

	/** What the function returned. */
	public JsonNode expectedOutput() {
		return Objects.requireNonNull(this.output, () -> this.pointer + " raised");
	}

	/** What the function raised. */
	public JsonNode expectedError() {
		return Objects.requireNonNull(this.error, () -> this.pointer + " returned");
	}

	/** The function's own name, without its module: {@code load_document}. */
	public String function() {
		return this.fn.substring(this.fn.lastIndexOf('.') + 1);
	}

	@Override
	public String toString() {
		return this.file + " " + this.pointer;
	}
}
