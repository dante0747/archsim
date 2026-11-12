package io.archsim.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.cli.RecordedCases.Ran;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim compare} against the frozen engine's CLI: each case of
 * {@code compare-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints what it printed, word for word.
 * Python's {@code compare} caught nothing: where it crashed, on a design or scenario it
 * could not simulate, the Java command refuses it as {@code run} does, with exit code 1.
 */
class CompareCommandTests {

	private static final String INVALID = "DocumentInvalidError: ";

	private static final String OVER_A_LIMIT = "SimulationLimitError: ";

	@TestFactory
	Stream<DynamicTest> compare_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("compare", folder);
	}

	@TestFactory
	Stream<DynamicTest> compare_eachFileRecordedAsUnreadable_isRefusedInOneLine(@TempDir Path folder) {
		return Replays.unreadableFiles("compare", folder);
	}

	// the refusal, and the issues with the first error Python's exception named
	@TestFactory
	Stream<DynamicTest> compare_eachDesignPythonCouldNotSimulate_isRefusedWithItsIssues(@TempDir Path folder) {
		return RecordedCases.of("compare")
			.stream()
			.filter((recorded) -> recorded.path("crash").stringValue("").startsWith(INVALID))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				String firstError = firstError(recorded.get("crash").stringValue());

				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.err())).containsExactly(1,
						"Cannot compare: document is invalid\n");
				assertThat(java.out()).contains(firstError);
			}));
	}

	// "DocumentInvalidError: <the first error> (+2 more)"
	private static String firstError(String crash) {
		String errors = crash.substring(INVALID.length());
		int more = errors.indexOf(" (+");
		return (more < 0) ? errors : errors.substring(0, more);
	}

	@TestFactory
	Stream<DynamicTest> compare_eachRunPythonFoundOverALimit_isRefusedInTheEnginesWords(@TempDir Path folder) {
		return RecordedCases.of("compare")
			.stream()
			.filter((recorded) -> recorded.path("crash").stringValue("").startsWith(OVER_A_LIMIT))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				String limit = recorded.get("crash").stringValue().substring(OVER_A_LIMIT.length());

				Ran java = RecordedCases.run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out(), java.err())).containsExactly(1, "",
						"Cannot compare: " + limit + "\n");
			}));
	}

}
