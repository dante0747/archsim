package io.archsim.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.cli.RecordedCases.Ran;
import io.archsim.model.text.PythonJson;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorded cases of a command ({@link RecordedCases}) as tests: Java must do what the
 * frozen engine's CLI did, word for word in its lines; its JSON with the same values as
 * the golden files compare them ({@link JsonDiff}: numbers to a relative 1e-9, an integer
 * and a float alike, keys in any order), laid out as {@code json.dumps(value, indent=2)}
 * lays it out.
 */
final class Replays {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private Replays() {
	}

	/**
	 * Each recorded case of a command run in Java, as a test: the same exit code, the
	 * same text on standard error and output, and the same files written. Left out: a
	 * case where Python crashed (its {@code crash}), whose Java answer is a departure
	 * tested with the command; a usage error, whose words are picocli's
	 * ({@link #usageErrors}); and the departures the command's tests name.
	 * @param command the command, such as {@code validate}
	 * @param folder where the cases' files are written
	 * @param departures the names of the cases the command's tests check otherwise
	 * @return a test for each case
	 */
	static Stream<DynamicTest> of(String command, Path folder, String... departures) {
		Set<String> skipped = Set.of(departures);
		return RecordedCases.of(command)
			.stream()
			.filter((recorded) -> !recorded.has("crash") && !recorded.has("usage")
					&& !skipped.contains(recorded.get("name").stringValue()))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.err())).containsExactly(recorded.get("exitCode").intValue(),
						recorded.get("stderr").stringValue());
				assertSameText(recorded.get("stdout").stringValue(), java.out());
				assertSameFiles(recorded.path("files"), java.written());
			}));
	}

	/**
	 * Each usage error recorded of a command, as a test: the same exit code, 2, nothing
	 * on standard output and picocli's words on standard error.
	 * @param command the command, such as {@code export}
	 * @param folder where the cases' files are written
	 * @return a test for each case
	 */
	static Stream<DynamicTest> usageErrors(String command, Path folder) {
		return RecordedCases.of(command)
			.stream()
			.filter((recorded) -> recorded.has("usage"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out())).containsExactly(recorded.get("exitCode").intValue(),
						recorded.get("stdout").stringValue());
				assertThat(java.err()).isNotBlank();
			}));
	}

	/**
	 * Each recorded case of a command whose file Python could not read (missing, or not
	 * JSON: it crashed, exit code 1), run in Java, as a test: the same exit code and
	 * standard output, and a one-line refusal on standard error that names the file.
	 * @param command the command, such as {@code validate}
	 * @param folder where the cases' files are written
	 * @return a test for each case
	 */
	static Stream<DynamicTest> unreadableFiles(String command, Path folder) {
		return RecordedCases.of(command)
			.stream()
			.filter((recorded) -> recorded.path("crash").stringValue("").startsWith("JSONDecodeError")
					|| recorded.path("crash").stringValue("").startsWith("FileNotFoundError"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out())).containsExactly(recorded.get("exitCode").intValue(),
						recorded.get("stdout").stringValue());
				assertThat(java.err().lines()).singleElement()
					.asString()
					.matches("Cannot read \\{(design|missing)\\}: .+");
			}));
	}

	// JSON (a design, a report) by its values and Python's layout; anything else word for
	// word, with Python's line ends
	private static void assertSameText(String python, String java) {
		String text = java.replace("\r\n", "\n");
		if (!python.startsWith("{") && !python.startsWith("[")) {
			assertThat(text).isEqualTo(python);
			return;
		}
		JsonNode printed = JSON.readTree(text);
		assertThat(JsonDiff.differences(JSON.readTree(python), printed)).isEmpty();
		assertThat(text).isEqualTo(PythonJson.writeIndented(printed, indent(python)) + "\n");
	}

	// the spaces Python indented its JSON by: two for what it printed and most files, one
	// for a recording of Prometheus responses
	private static int indent(String python) {
		String second = python.lines().skip(1).findFirst().orElse("");
		return second.length() - second.stripLeading().length();
	}

	private static void assertSameFiles(JsonNode recorded, Map<String, String> written) {
		assertThat(written.keySet()).containsAll(recorded.propertyNames());
		for (Map.Entry<String, JsonNode> file : recorded.properties()) {
			assertSameText(file.getValue().stringValue(), written.getOrDefault(file.getKey(), ""));
		}
	}

}
