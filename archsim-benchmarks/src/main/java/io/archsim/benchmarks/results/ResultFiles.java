package io.archsim.benchmarks.results;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;
import io.archsim.model.text.PythonJson;

/**
 * The results' JSON files, read and written as the Python harness read and wrote them:
 * read as {@code json.loads} reads them (an integer stays an integer), written as
 * {@code json.dumps(data, indent=2)} and a newline, in UTF-8 with LF line ends
 * (ADR-0070).
 */
public final class ResultFiles {

	private static final int INDENT = 2;

	private ResultFiles() {
	}

	/**
	 * A file's value, when it is there.
	 * @param path the file
	 * @return its value; empty when there is no such file
	 */
	public static Optional<JsonNode> read(Path path) {
		if (!Files.exists(path)) {
			return Optional.empty();
		}
		try {
			return Optional.of(PythonJsonReader.read(Files.readString(path, StandardCharsets.UTF_8)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Writes a value to a file, over what was there.
	 * @param path the file
	 * @param data the value
	 */
	public static void write(Path path, JsonNode data) {
		try {
			Files.writeString(path, text(data), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A value as a results file holds it.
	 * @param data the value
	 * @return the file's text
	 */
	public static String text(JsonNode data) {
		return PythonJson.writeIndented(data, INDENT) + "\n";
	}

}
