package io.archsim.cli;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code archsim validate} against the frozen engine's CLI: each case of
 * {@code validate-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints what it printed, word for word.
 */
class ValidateCommandTests {

	@TestFactory
	Stream<DynamicTest> validate_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("validate", folder);
	}

	// Python crashed with a traceback, exit code 1
	@TestFactory
	Stream<DynamicTest> validate_eachFileRecordedAsUnreadable_isRefusedInOneLine(@TempDir Path folder) {
		return Replays.unreadableFiles("validate", folder);
	}

}
