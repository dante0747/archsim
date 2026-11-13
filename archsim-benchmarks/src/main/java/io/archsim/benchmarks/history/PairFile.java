package io.archsim.benchmarks.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file of pairs under a header, such as a rewrite's commit map or the tree map: read
 * line by line and refused at the first line that is not a pair, or that names a key
 * twice.
 */
final class PairFile {

	private PairFile() {
	}

	/**
	 * The pairs in a file.
	 * @param file the file
	 * @param format its header and its pairs
	 * @return each pair's value by its key, in the file's order
	 * @throws IllegalArgumentException when the file is not there or has no header, or a
	 * line is not a pair or names a key twice; the message names the file and the line
	 */
	static Map<String, String> read(Path file, Format format) {
		List<String> lines = lines(file);
		if (lines.isEmpty() || !format.header().matcher(lines.getFirst()).matches()) {
			throw new IllegalArgumentException(
					file + ": the first line is not a header such as '" + format.example() + "'");
		}
		Map<String, String> pairs = new LinkedHashMap<>();
		for (int i = 1; i < lines.size(); i++) {
			Matcher pair = format.pair().matcher(lines.get(i));
			if (!pair.matches()) {
				throw new IllegalArgumentException(
						file + ":" + (i + 1) + ": not two " + format.noun() + ": '" + lines.get(i) + "'");
			}
			if (pairs.put(pair.group(1), pair.group(2)) != null) {
				throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + pair.group(1) + " is mapped twice");
			}
		}
		return pairs;
	}

	private static List<String> lines(Path file) {
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8);
		}
		catch (NoSuchFileException ex) {
			throw new IllegalArgumentException(file + ": no such file", ex);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * What a file of pairs holds.
	 *
	 * @param header its first line
	 * @param example a header, for messages
	 * @param pair a line of a pair: its key, then its value
	 * @param noun what a pair is of, for messages: {@code IDs}, {@code trees}
	 */
	record Format(Pattern header, String example, Pattern pair, String noun) {

	}

}
