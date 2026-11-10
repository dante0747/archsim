package io.archsim.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.model.text.PythonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The files {@code calibrate} reads, as Python's {@code Path.read_text(encoding="utf-8")}
 * and {@code json.loads} read them, and refused in the words of Python's exceptions, with
 * the file named as {@code pathlib} names it.
 */
class PythonFilesTests {

	private static final String SEPARATOR = File.separator;

	@Test
	void readText_windowsAndOldMacLineEnds_areLineFeeds(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("m.yaml"), "a\r\nb\rc\n".getBytes(StandardCharsets.UTF_8));

		assertThat(PythonFiles.readText(file.toString())).isEqualTo("a\nb\nc\n");
	}

	@Test
	void readJson_textNotJsonAfterWindowsLineEnds_isRefusedWhereItIsOnceTheyAreLineFeeds(@TempDir Path folder)
			throws IOException {
		Path file = Files.write(folder.resolve("d.json"), "\r\n\r\n x".getBytes(StandardCharsets.UTF_8));

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readJson(file.toString()))
			.withMessage("Expecting value: line 3 column 2 (char 3)");
	}

	@Test
	void readText_aByteOrderMark_isKeptAsReadTextKeepsIt(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("m.yaml"), new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x' });

		assertThat(PythonFiles.readText(file.toString())).isEqualTo("\ufeffx");
	}

	@Test
	void readJson_aByteOrderMark_isRefusedInJsonsWords(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("d.json"),
				new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}' });

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readJson(file.toString()))
			.withMessage("Unexpected UTF-8 BOM (decode using utf-8-sig): line 1 column 1 (char 0)");
	}

	@Test
	void readText_bytesThatAreNotUtf8_areRefusedInTheCodecsWords(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("m.yaml"), new byte[] { 'a', (byte) 0xFF });

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readText(file.toString()))
			.withMessage("'utf-8' codec can't decode byte 0xff in position 1: invalid start byte");
	}

	@Test
	void readJson_nestingDeeperThanItReads_isRefusedForItsDepth(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("d.json"), "[".repeat(1001) + "]".repeat(1001));

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readJson(file.toString()))
			.withMessage("the JSON nests more than 1000 levels deep");
	}

	@Test
	void readText_aMissingFile_isNamedAsPathlibNamesIt() {
		assertThatExceptionOfType(PythonFileException.class)
			.isThrownBy(() -> PythonFiles.readText("./no-such-mapping.yaml"))
			.withMessage("[Errno 2] No such file or directory: 'no-such-mapping.yaml'");
	}

	@Test
	void readText_aFolder_isRefusedAsPythonsOpenRefusesItThere(@TempDir Path folder) {
		String name = PythonText.repr(folder.toString());
		String refusal = OS.WINDOWS.isCurrentOs() ? "[Errno 13] Permission denied: " : "[Errno 21] Is a directory: ";

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readText(folder.toString()))
			.withMessage(refusal + name);
	}

	@Test
	void writeText_aFile_isWrittenInUtf8WithItsLineFeeds(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("check.md");

		PythonFiles.writeText(file.toString(), "\u2705 a\nb\n");

		assertThat(Files.readAllBytes(file)).isEqualTo("\u2705 a\nb\n".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void writeText_aFolder_isRefusedAsPythonsOpenRefusesItThere(@TempDir Path folder) {
		String refusal = OS.WINDOWS.isCurrentOs() ? "[Errno 13] Permission denied: " : "[Errno 21] Is a directory: ";

		assertThatExceptionOfType(PythonFileException.class)
			.isThrownBy(() -> PythonFiles.writeText(folder.toString(), "x"))
			.withMessage(refusal + PythonText.repr(folder.toString()));
	}

	@Test
	void writeText_aFileInAFolderThatIsNotThere_isPythonsNoSuchFile(@TempDir Path folder) {
		Path file = folder.resolve("nowhere").resolve("check.md");

		assertThatExceptionOfType(PythonFileException.class)
			.isThrownBy(() -> PythonFiles.writeText(file.toString(), "x"))
			.withMessage("[Errno 2] No such file or directory: " + PythonText.repr(file.toString()));
	}

	@Test
	void readText_noName_isTheCurrentFolder() {
		String refusal = OS.WINDOWS.isCurrentOs() ? "[Errno 13] Permission denied: " : "[Errno 21] Is a directory: ";

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readText(""))
			.withMessage(refusal + "'.'");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void readText_aNameWindowsRefuses_isPythonsInvalidArgument() {
		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readText("a?b"))
			.withMessage("[Errno 22] Invalid argument: 'a?b'");
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void readText_aFileItMayNotRead_isPythonsPermissionDenied(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("m.yaml"), "a: 1");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
		assumeThat(Files.isReadable(file)).as("not running as root").isFalse();

		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonFiles.readText(file.toString()))
			.withMessage("[Errno 13] Permission denied: " + PythonText.repr(file.toString()));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "./a|a", "a/./b|a/b", "a/../b|a/../b", "a//b/|a/b", "''|.", ".|.", "./.|." })
	void name_aPath_isNamedAsPathlibNamesIt(String given, String named) {
		assertThat(PythonFiles.name(given)).isEqualTo(named.replace("/", SEPARATOR));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "dir/x.json|x.json", "x.json|x.json", "''|''", ".|''", "dir/..|.." })
	void fileName_aPath_isItsLastNameAsPathlibGivesIt(String given, String named) {
		assertThat(PythonFiles.fileName(given)).isEqualTo(named);
	}

	@ParameterizedTest
	@EnabledOnOs(OS.WINDOWS)
	@CsvSource(delimiter = '|',
			value = { "//host/share/./m.yaml|\\\\host\\share\\m.yaml|m.yaml", "//host/share|\\\\host\\share\\|''" })
	void name_aUncPath_keepsItsServerAndShareAsTheDrive(String given, String named, String last) {
		assertThat(List.of(PythonFiles.name(given), PythonFiles.fileName(given))).containsExactly(named, last);
	}

}
