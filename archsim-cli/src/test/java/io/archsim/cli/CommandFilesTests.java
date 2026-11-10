package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The files the commands read and write: where the Python CLI crashed with a traceback (a
 * missing file, text that is not JSON or not UTF-8, a file it could not write), the Java
 * CLI says which file and why.
 */
class CommandFilesTests {

	/** Deeper than the nesting Jackson reads by default. */
	private static final int TOO_DEEP = 600;

	@TempDir
	private Path folder;

	@Test
	void readJson_aMissingFile_isRefusedWithItsName() {
		Path missing = this.folder.resolve("missing.json");

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readJson(missing))
			.withMessage("Cannot read %s: there is no such file or folder", missing);
	}

	@Test
	void readJson_textThatIsNotJson_isRefusedWithWhereItStops() throws IOException {
		Path file = Files.writeString(this.folder.resolve("design.json"), "{\"a\": 1,\n oops}");

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readJson(file))
			.withMessageStartingWith("Cannot read %s: it is not JSON (", file)
			.withMessageEndingWith(" at line 2, column 2)");
	}

	@Test
	void readJson_anEmptyFile_isRefusedAsNotJson() throws IOException {
		Path file = Files.writeString(this.folder.resolve("design.json"), " \n");

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readJson(file))
			.withMessage("Cannot read %s: it is not JSON (it holds no value)", file);
	}

	@Test
	void readJson_nestingDeeperThanJacksonReads_isRefusedWithJacksonsWords() throws IOException {
		Path file = Files.writeString(this.folder.resolve("design.json"), "[".repeat(TOO_DEEP) + "]".repeat(TOO_DEEP));

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readJson(file))
			.withMessageStartingWith("Cannot read %s: Document nesting depth", file);
	}

	@Test
	void readJson_textThatIsNotUtf8_isRefused() throws IOException {
		Path file = Files.write(this.folder.resolve("design.json"), new byte[] { '"', (byte) 0xff, '"' });

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readJson(file))
			.withMessage("Cannot read %s: it is not UTF-8 text", file);
	}

	@Test
	void readJson_nanAndTheInfinities_areReadAsPythonReadsThem() throws IOException {
		Path file = Files.writeString(this.folder.resolve("design.json"), "[NaN, Infinity, -Infinity]");

		JsonNode value = CommandFiles.readJson(file);

		assertThat(value.valueStream().map(JsonNode::doubleValue)).containsExactly(Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY);
	}

	@Test
	void readBytes_aMissingFile_isRefusedWithItsName() {
		Path missing = this.folder.resolve("missing.yml");

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readBytes(missing.toString()))
			.withMessage("Cannot read %s: there is no such file or folder", missing);
	}

	@Test
	void readBytes_aNameThatIsNotAPath_isRefusedWithTheReason() {
		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.readBytes("design\0.json"))
			.withMessageStartingWith("Cannot read design\0.json: ");
	}

	@Test
	void write_intoAFolderThatIsNotThere_isRefusedWithTheFilesName() {
		Path file = this.folder.resolve("nowhere").resolve("report.json");

		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.write(file.toString(), "{}"))
			.withMessage("Cannot write %s: there is no such file or folder", file);
	}

	// Linux names the file in its reason; Windows denies access instead
	@Test
	void write_aFolder_isRefusedWithTheReasonOnce() {
		assertThatExceptionOfType(CommandException.class)
			.isThrownBy(() -> CommandFiles.write(this.folder.toString(), "{}"))
			.withMessageStartingWith("Cannot write %s: ", this.folder)
			.withMessageNotContaining(this.folder + ": " + this.folder);
	}

	@Test
	void write_aNameThatIsNotAPath_isRefusedWithTheReason() {
		assertThatExceptionOfType(CommandException.class).isThrownBy(() -> CommandFiles.write("report\0.json", "{}"))
			.withMessageStartingWith("Cannot write report\0.json: ");
	}

}
