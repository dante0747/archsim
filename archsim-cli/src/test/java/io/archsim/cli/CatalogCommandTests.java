package io.archsim.cli;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code archsim catalog} against the frozen engine's CLI: each case of
 * {@code catalog-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints what it printed, word for word.
 */
class CatalogCommandTests {

	@TestFactory
	Stream<DynamicTest> catalog_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("catalog", folder);
	}

}
