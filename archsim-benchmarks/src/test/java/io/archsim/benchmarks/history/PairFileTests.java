package io.archsim.benchmarks.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A file of pairs under a header, in a format of letters separated by a colon.
 */
class PairFileTests {

	private static final PairFile.Format FORMAT = new PairFile.Format(Pattern.compile("key:value"), "key:value",
			Pattern.compile("(\\w+):(\\w+)"), "words");

	@Test
	void read_pairsUnderTheirHeader_areEachValueByItsKeyInOrder(@TempDir Path folder) throws IOException {
		Map<String, String> pairs = PairFile.read(write(folder, "key:value\nb:two\na:one\n"), FORMAT);

		assertThat(pairs).containsExactly(Map.entry("b", "two"), Map.entry("a", "one"));
	}

	// without a header the first pair would be lost
	@Test
	void read_aFileWithoutItsHeader_isRefused(@TempDir Path folder) throws IOException {
		Path file = write(folder, "a:one\n");

		assertThatIllegalArgumentException().isThrownBy(() -> PairFile.read(file, FORMAT))
			.withMessageContaining("the first line is not a header such as 'key:value'");
	}

	@Test
	void read_aLineThatIsNotAPair_isRefusedByItsNumber(@TempDir Path folder) throws IOException {
		Path file = write(folder, "key:value\na:one\nnot a pair\n");

		assertThatIllegalArgumentException().isThrownBy(() -> PairFile.read(file, FORMAT))
			.withMessageContaining("pairs.txt:3: not two words: 'not a pair'");
	}

	@Test
	void read_aKeyMappedTwice_isRefusedByItsNumber(@TempDir Path folder) throws IOException {
		Path file = write(folder, "key:value\na:one\na:two\n");

		assertThatIllegalArgumentException().isThrownBy(() -> PairFile.read(file, FORMAT))
			.withMessageContaining("pairs.txt:3: a is mapped twice");
	}

	@Test
	void read_aFileThatIsNotThere_isRefused(@TempDir Path folder) {
		Path file = folder.resolve("pairs.txt");

		assertThatIllegalArgumentException().isThrownBy(() -> PairFile.read(file, FORMAT))
			.withMessage(file + ": no such file");
	}

	private static Path write(Path folder, String text) throws IOException {
		Path file = folder.resolve("pairs.txt");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
