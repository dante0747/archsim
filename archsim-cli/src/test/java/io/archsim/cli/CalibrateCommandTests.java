package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.cli.RecordedCases.Ran;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim calibrate} against the frozen engine's CLI: each case of
 * {@code calibrate-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints, or writes, what it did, word for
 * word: from recorded responses, a Prometheus server (a stub the case describes) and
 * OpenTelemetry traces, each refusal in Python's words.
 */
class CalibrateCommandTests {

	/** Mappings that are not YAML: SnakeYAML's words, not PyYAML's (ADR-0043). */
	private static final List<String> NOT_YAML = List.of("aMappingThatIsNotYaml", "anOtlpMappingThatIsNotYaml");

	/**
	 * Recorded responses that are not JSON past their first character: Jackson's
	 * description of the problem, in Python's form (ADR-0044).
	 */
	private static final List<String> NOT_JSON = List.of("aRecordingThatIsNotJson",
			"aRecordingThatIsNotJsonAndARangeThatIsNotOne");

	/**
	 * Steps the range refuses, given or recorded: Python printed pydantic's whole error,
	 * over four lines, with the bound's words on the third.
	 */
	private static final List<String> STEPS_REFUSED = List.of("aStepOfZero", "aNegativeStep", "aStepAboveADay",
			"aRecordingWhoseStepIsRefused");

	/**
	 * Steps that are not finite numbers: Python read them as floats and refused them by
	 * the bound; Java refuses them as not finite, before any bound (ADR-0103).
	 */
	private static final List<String> STEPS_NOT_FINITE = List.of("aStepThatIsNan", "aStepThatIsInfinite");

	/** Recordings that are not objects, which Python failed to read (AttributeError). */
	private static final List<String> RECORDINGS_NOT_OBJECTS = List.of("aRecordingThatIsAList", "aRecordingThatIsNull");

	@TestFactory
	Stream<DynamicTest> calibrate_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		List<String> departures = new ArrayList<>(NOT_YAML);
		departures.addAll(NOT_JSON);
		departures.addAll(STEPS_REFUSED);
		departures.addAll(STEPS_NOT_FINITE);
		return Replays.of("calibrate", folder, departures.toArray(String[]::new));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_aStepTheRangeRefuses_isRefusedInPydanticsWordsForTheBoundOnOneLine(
			@TempDir Path folder) {
		return STEPS_REFUSED.stream().map((name) -> DynamicTest.dynamicTest(name, () -> {
			JsonNode recorded = RecordedCases.named("calibrate", name);
			String line = recorded.get("stderr").stringValue().lines().toList().get(2).strip();
			String bound = line.substring(0, line.indexOf(" [type="));

			Ran java = RecordedCases.run(recorded, folder);

			assertThat(List.of(java.exitCode(), java.out())).containsExactly(1, "");
			assertThat(java.err()).isEqualTo("Cannot calibrate: step: " + bound + "\n");
		}));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_aStepThatIsNotFinite_isRefusedAsNotFiniteOnOneLine(@TempDir Path folder) {
		return STEPS_NOT_FINITE.stream().map((name) -> DynamicTest.dynamicTest(name, () -> {
			Ran java = run(name, folder);

			assertThat(List.of(java.exitCode(), java.out(), java.err())).containsExactly(1, "",
					"Cannot calibrate: step: Input should be a finite number\n");
		}));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_aRecordingThatIsNotAnObject_isRefusedPlainly(@TempDir Path folder) {
		return RECORDINGS_NOT_OBJECTS.stream().map((name) -> DynamicTest.dynamicTest(name, () -> {
			Ran java = run(name, folder);

			assertThat(List.of(java.exitCode(), java.out(), java.err())).containsExactly(1, "",
					"Cannot calibrate: a recording must be a JSON object, with its 'responses'\n");
		}));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_aMappingPythonCrashedOn_isRefusedInItsExceptionsWords(@TempDir Path folder) {
		return RecordedCases.of("calibrate")
			.stream()
			.filter((recorded) -> recorded.has("crash")
					&& !RECORDINGS_NOT_OBJECTS.contains(recorded.get("name").stringValue()))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				String crash = recorded.get("crash").stringValue();

				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out())).containsExactly(1, "");
				assertThat(java.err())
					.isEqualTo("Cannot calibrate: " + crash.substring(crash.indexOf(": ") + 2) + "\n");
			}));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_eachUsageErrorRecorded_exitsWithCode2(@TempDir Path folder) {
		return Replays.usageErrors("calibrate", folder);
	}

	@TestFactory
	Stream<DynamicTest> calibrate_aMappingThatIsNotYaml_isRefusedInTheYamlLibrarysWords(@TempDir Path folder) {
		return NOT_YAML.stream().map((name) -> DynamicTest.dynamicTest(name, () -> {
			Ran java = run(name, folder);

			assertThat(List.of(java.exitCode(), java.out())).containsExactly(1, "");
			assertThat(java.err()).startsWith("Cannot calibrate: the ").contains("mapping is not valid YAML: ");
		}));
	}

	@TestFactory
	Stream<DynamicTest> calibrate_recordedResponsesThatAreNotJson_areRefusedInJacksonsWordsAtPythonsPosition(
			@TempDir Path folder) {
		return NOT_JSON.stream().map((name) -> DynamicTest.dynamicTest(name, () -> {
			Ran java = run(name, folder);

			assertThat(List.of(java.exitCode(), java.out())).containsExactly(1, "");
			assertThat(java.err().lines()).singleElement()
				.asString()
				.startsWith("Cannot calibrate: ")
				.endsWith(pythonsPosition(name));
		}));
	}

	private static Ran run(String name, Path folder) throws IOException {
		return RecordedCases.run(RecordedCases.named("calibrate", name), folder);
	}

	// where Python's json said the text stops being JSON: ": line 1 column 2 (char 1)"
	private static String pythonsPosition(String name) {
		String python = RecordedCases.named("calibrate", name).get("stderr").stringValue().strip();
		return python.substring(python.lastIndexOf(": line "));
	}

}
