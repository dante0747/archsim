package io.archsim.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.cli.RecordedCases.Ran;
import io.archsim.engine.parity.ReportComparison;
import io.archsim.engine.parity.WordingDepartures;
import io.archsim.model.text.PythonJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim run} against the frozen engine's CLI: each run of
 * {@code run-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints what it printed, word for word, on
 * standard output and standard error, but for a count of one request, which the Java
 * words in the singular ({@link WordingDepartures}); a report written to a file holds
 * what Python's held, compared as the simulator's recorded runs are
 * ({@link ReportComparison}).
 */
class RunCommandTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	// a count of one request in the singular, where Python wrote "1 requests failed"
	@TestFactory
	Stream<DynamicTest> run_eachRecordedRun_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("run", folder, WordingDepartures::asJavaWordsIt);
	}

	@TestFactory
	Stream<DynamicTest> run_aReportWrittenToAFile_holdsWhatThePythonReportHeld(@TempDir Path folder) {
		return RecordedCases.of("run")
			.stream()
			.filter((recorded) -> recorded.has("report"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran ran = RecordedCases.run(recorded, folder);

				JsonNode written = JSON.readTree(Files.readString(ran.report()));
				assertThat(ReportComparison.differences(recorded.get("report"), written)).isEmpty();
			}));
	}

	@TestFactory
	Stream<DynamicTest> run_aReportWrittenToAFile_isWrittenAsPythonsJsonDumpsWritesIt(@TempDir Path folder) {
		return RecordedCases.of("run")
			.stream()
			.filter((recorded) -> recorded.has("report"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran ran = RecordedCases.run(recorded, folder);

				String written = Files.readString(ran.report());
				assertThat(written)
					.isEqualTo(PythonJson.writeIndented(JSON.readTree(written), RecordedCases.PYTHON_INDENT));
			}));
	}

}
