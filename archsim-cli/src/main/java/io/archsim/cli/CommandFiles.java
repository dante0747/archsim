package io.archsim.cli;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The files a command reads and writes. Where the Python CLI crashed with a traceback (a
 * file missing or unwritable, text that is not UTF-8 or not JSON), a
 * {@link CommandException} says which file and why. JSON is read as Python's
 * {@code json.loads} reads it, NaN and the infinities included.
 */
final class CommandFiles {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
		.build();

	private CommandFiles() {
	}

	/**
	 * The JSON value a file holds.
	 * @param file the file
	 * @return its value
	 * @throws CommandException when the file cannot be read, is not UTF-8 or is not JSON
	 */
	static JsonNode readJson(Path file) {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new CommandException("Cannot read %s: %s".formatted(file, reason(ex)));
		}
		JsonNode value;
		try {
			value = JSON.readTree(text);
		}
		catch (StreamConstraintsException ex) {
			throw new CommandException("Cannot read %s: %s".formatted(file, ex.getOriginalMessage()));
		}
		catch (JacksonException ex) {
			throw new CommandException("Cannot read %s: it is not JSON (%s%s)".formatted(file, ex.getOriginalMessage(),
					where(ex.getLocation())));
		}
		if (value.isMissingNode()) {
			throw new CommandException("Cannot read %s: it is not JSON (it holds no value)".formatted(file));
		}
		return value;
	}

	/**
	 * Writes text to a file in UTF-8, replacing what it held.
	 * @param file the file, as the command line names it
	 * @param text the text
	 * @throws CommandException when the file cannot be written, or its name is not one
	 */
	static void write(String file, String text) {
		try {
			Files.writeString(Path.of(file), text, StandardCharsets.UTF_8);
		}
		catch (InvalidPathException ex) {
			throw new CommandException("Cannot write %s: %s".formatted(file, ex.getReason()));
		}
		catch (IOException ex) {
			throw new CommandException("Cannot write %s: %s".formatted(file, reason(ex)));
		}
	}

	// the file system's reason without the file's name, which the refusal gives once
	private static String reason(IOException ex) {
		return switch (ex) {
			case NoSuchFileException _ -> "there is no such file or folder";
			case AccessDeniedException _ -> "access is denied";
			case CharacterCodingException _ -> "it is not UTF-8 text";
			case FileSystemException system when system.getReason() != null -> system.getReason();
			default -> String.valueOf(ex.getMessage());
		};
	}

	// some of Jackson's failures carry no location: then none is given
	private static String where(@Nullable TokenStreamLocation location) {
		if (location == null || location.getLineNr() < 1) {
			return "";
		}
		return " at line %d, column %d".formatted(location.getLineNr(), location.getColumnNr());
	}

}
