package io.archsim.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.cli.RecordedCases.Ran;
import io.archsim.engine.parity.CostDepartures;
import io.archsim.model.text.PythonJson;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorded cases of a command ({@link RecordedCases}) as tests: Java must do what the
 * frozen engine's CLI did, word for word in its lines; its JSON with the same values as
 * the golden files compare them ({@link JsonDiff}: numbers to a relative 1e-9, an integer
 * and a float alike, keys in any order; a cost as {@link CostDepartures} compares one,
 * ADR-0074), laid out as {@code json.dumps(value, indent=2)} lays it out. What it prints
 * is compared as the frozen engine would have printed it at its schema
 * ({@link SchemaDepartures}).
 */
final class Replays {

	/** Python's JSON, NaN and the infinities included, as json.dumps writes them. */
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
		.build();

	private static final String COST = "cost";

	private static final Pattern INPUT_DIGEST = Pattern.compile("inputDigest=[0-9a-f]{12}");

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
		return of(command, folder, UnaryOperator.identity(), departures);
	}

	/**
	 * Each recorded case of a command run in Java, as a test, against what Python printed
	 * as Java prints it: for a command whose text names what differs by design, such as
	 * the engine's version.
	 * @param command the command, such as {@code check}
	 * @param folder where the cases' files are written
	 * @param asJavaPrints Python's text (standard output and error, a file) as Java
	 * prints it
	 * @param departures the names of the cases the command's tests check otherwise
	 * @return a test for each case
	 */
	static Stream<DynamicTest> of(String command, Path folder, UnaryOperator<String> asJavaPrints,
			String... departures) {
		Set<String> skipped = Set.of(departures);
		return RecordedCases.of(command)
			.stream()
			.filter((recorded) -> !recorded.has("crash") && !recorded.has("usage")
					&& !skipped.contains(recorded.get("name").stringValue()))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), SchemaDepartures.asRecordedText(java.err()))).containsExactly(
						recorded.get("exitCode").intValue(), asJavaPrints.apply(recorded.get("stderr").stringValue()));
				assertSameText(asJavaPrints.apply(recorded.get("stdout").stringValue()), java.out());
				assertSameFiles(recorded.path("files"), java.written(), asJavaPrints);
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
			assertThat(withoutInputDigest(SchemaDepartures.asRecordedText(text))).isEqualTo(withoutInputDigest(python));
			return;
		}
		JsonNode expected = JSON.readTree(python);
		JsonNode printed = JSON.readTree(text);
		JsonNode compared = withCostAsFrozen(expected, SchemaDepartures.asRecorded(printed));
		// both NaN is the same value: json.dumps and Java print it alike, but the golden
		// files' comparison, like Python's, finds NaN unequal to itself
		assertThat(JsonDiff.differences(expected, compared))
			.filteredOn((pointer) -> !(isNan(expected.at(pointer)) && isNan(compared.at(pointer))))
			.isEmpty();
		assertThat(text).isEqualTo(PythonJson.writeIndented(printed, indent(python)) + "\n");
	}

	// a design's analysis or a run's report holds a cost, compared with its departures
	// put back
	private static JsonNode withCostAsFrozen(JsonNode expected, JsonNode printed) {
		if (!(printed instanceof ObjectNode object) || !object.path(COST).isObject()) {
			return printed;
		}
		ObjectNode copy = object.deepCopy();
		copy.set(COST,
				CostDepartures.comparable(expected.path(COST), object.path(COST), CostDepartures.Departing.NONE));
		return copy;
	}

	// archsim run prints the digest of its input, a schema 1.8 design where Python's
	// was 1.7 (ADR-0074), so its text is compared without it; a report written to a
	// file checks the digest (ReportComparison)
	private static String withoutInputDigest(String text) {
		return INPUT_DIGEST.matcher(text).replaceAll("inputDigest=");
	}

	private static boolean isNan(JsonNode value) {
		return value.isNumber() && Double.isNaN(value.doubleValue());
	}

	// the spaces Python indented its JSON by: two for what it printed and most files, one
	// for a recording of Prometheus responses
	private static int indent(String python) {
		String second = python.lines().skip(1).findFirst().orElse("");
		return second.length() - second.stripLeading().length();
	}

	private static void assertSameFiles(JsonNode recorded, Map<String, String> written,
			UnaryOperator<String> asJavaPrints) {
		assertThat(written.keySet()).containsAll(recorded.propertyNames());
		for (Map.Entry<String, JsonNode> file : recorded.properties()) {
			assertSameText(asJavaPrints.apply(file.getValue().stringValue()), written.getOrDefault(file.getKey(), ""));
		}
	}

}
