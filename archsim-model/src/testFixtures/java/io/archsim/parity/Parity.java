package io.archsim.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Checks a Java function against one golden entry: the same output, or the same error.
 */
public final class Parity {

	private Parity() {
	}

	/**
	 * Calls the Java counterpart of the entry's function and checks what it gives against
	 * what the Python engine gave.
	 * @param entry the recorded call
	 * @param call the Java counterpart: the output as the golden file records it, or it
	 * throws
	 * @param error how a thrown exception is recorded: {@code {type, message}} and maybe
	 * more, with the Python exception's type name
	 */
	public static void check(GoldenEntry entry, Function<GoldenEntry, JsonNode> call,
			Function<RuntimeException, JsonNode> error) {
		JsonNode actual;
		try {
			actual = call.apply(entry);
		}
		catch (RuntimeException e) {
			if (!entry.raised()) {
				throw new AssertionError(entry + ": the Python engine returned, Java threw " + e, e);
			}
			assertThat(JsonDiff.differences(entry.expectedError(), error.apply(e)))
				.as("%s: the error differs from the Python engine's at", entry)
				.isEmpty();
			return;
		}
		if (entry.raised()) {
			fail("%s: the Python engine raised %s, Java returned %s", entry, entry.expectedError(), actual);
		}
		assertThat(JsonDiff.differences(entry.expectedOutput(), actual))
			.as("%s: the output differs from the Python engine's at", entry)
			.isEmpty();
	}

}
