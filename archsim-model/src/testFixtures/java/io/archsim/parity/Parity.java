package io.archsim.parity;

import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Checks a Java function against one golden entry: the same output, or the same error, as
 * the frozen engine would have written it at its schema ({@link SchemaDepartures}).
 */
public final class Parity {

	private Parity() {
	}

	/**
	 * Calls the Java counterpart of the entry's function and checks what it gives against
	 * what the Python engine gave.
	 * @param entry the recorded call
	 * @param call the Java counterpart: the output as the golden file records it, or it
	 * throws the {@link PythonError} the Python engine raises in its place
	 */
	public static void check(GoldenEntry entry, Function<GoldenEntry, JsonNode> call) {
		JsonNode actual;
		try {
			actual = call.apply(entry);
		}
		catch (PythonError ex) {
			checkError(entry, ex);
			return;
		}
		if (entry.raised()) {
			fail("%s: the Python engine raised %s, Java returned %s", entry, entry.expectedError(), actual);
		}
		assertThat(JsonDiff.differences(entry.expectedOutput(),
				SchemaDepartures.asRecorded(actual, entry.expectedOutput())))
			.as("%s: the output differs from the Python engine's at", entry)
			.isEmpty();
	}

	private static void checkError(GoldenEntry entry, PythonError error) {
		if (!entry.raised()) {
			Throwable thrown = error.getCause();
			throw new AssertionError(entry + ": the Python engine returned, Java threw " + thrown, thrown);
		}
		assertThat(JsonDiff.differences(entry.expectedError(), SchemaDepartures.asRecorded(error.error())))
			.as("%s: the error differs from the Python engine's at", entry)
			.isEmpty();
	}

}
