package io.archsim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.cli.RecordedCases.Ran;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim import} against the frozen engine's CLI: each case of
 * {@code import-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints, or writes, what it did, word for
 * word: from a file, standard input, UTF-16 or UTF-8 with a byte order mark, and the
 * codec's words for bytes that are neither.
 */
class ImportCommandTests {

	/** YAML that is not: SnakeYAML's words, not PyYAML's (ADR-0043). */
	private static final String NOT_YAML = "notYaml";

	/**
	 * Terraform text that is not JSON past its first character: Jackson's description of
	 * the problem, in Python's form (ADR-0044).
	 */
	private static final String NOT_JSON = "terraformThatIsNotJson";

	@TestFactory
	Stream<DynamicTest> import_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("import", folder, NOT_YAML, NOT_JSON);
	}

	@TestFactory
	Stream<DynamicTest> import_eachUsageErrorRecorded_exitsWithCode2(@TempDir Path folder) {
		return Replays.usageErrors("import", folder);
	}

	@TestFactory
	Stream<DynamicTest> import_eachFileRecordedAsUnreadable_isRefusedInOneLine(@TempDir Path folder) {
		return Replays.unreadableFiles("import", folder);
	}

	@Test
	void import_textThatIsNotYaml_isRefusedInTheYamlLibrarysWords(@TempDir Path folder) throws IOException {
		Ran java = RecordedCases.run(RecordedCases.named("import", NOT_YAML), folder);

		assertThat(List.of(java.exitCode(), java.out())).containsExactly(2, "");
		assertThat(java.err()).startsWith("cannot import: not valid YAML: ");
	}

	@Test
	void import_terraformThatIsNotJson_isRefusedInJacksonsWordsInPythonsForm(@TempDir Path folder) throws IOException {
		Ran java = RecordedCases.run(RecordedCases.named("import", NOT_JSON), folder);

		assertThat(List.of(java.exitCode(), java.out())).containsExactly(2, "");
		assertThat(java.err())
			.startsWith("cannot import: not JSON: run `terraform show -json plan.out` (or state) first (")
			.endsWith(": line 1 column 2 (char 1))\n");
	}

	@Test
	void import_standardInputThatCannotBeRead_isRefusedInOneLine() {
		InputStream broken = new InputStream() {

			@Override
			public int read() throws IOException {
				throw new IOException("the pipe is broken");
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				throw new IOException("the pipe is broken");
			}

		};
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();

		int exitCode = Archsim.commandLine(broken, Clock.systemUTC())
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute("import", "compose", "-");

		assertThat(List.of(exitCode, out.toString(), err.toString().strip())).containsExactly(1, "",
				"Cannot read standard input: the pipe is broken");
	}

}
