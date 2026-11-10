package io.archsim.cli;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code archsim schema} against the frozen engine's CLI: each case of
 * {@code schema-cases.json.gz} ({@code scripts/export_cli_cases.py}) exits with the code
 * Python's did and prints what it printed, word for word.
 */
class SchemaCommandTests {

	@TestFactory
	Stream<DynamicTest> schema_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return RecordedCases.replays("schema", folder);
	}

}
