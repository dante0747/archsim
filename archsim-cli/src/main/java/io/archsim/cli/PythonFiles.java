package io.archsim.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.JsonTooDeepException;
import io.archsim.engine.text.PythonJsonReader;
import io.archsim.model.text.PythonText;

/**
 * The files {@code calibrate} reads, where the Python CLI caught what went wrong and
 * printed it, read as Python read them and refused in the words its exceptions said:
 * {@code [Errno 2] No such file or directory: 'mapping.yaml'}, a codec's
 * {@code 'utf-8' codec can't decode byte 0xff in position 0: invalid start byte}, or
 * {@code json}'s {@code Expecting value: line 1 column 1 (char 0)}. A file is named as
 * {@code pathlib} names it: {@code ./mapping.yaml} is {@code mapping.yaml}.
 */
final class PythonFiles {

	private static final boolean WINDOWS = File.separatorChar == '\\';

	private static final Pattern SEPARATOR = Pattern.compile(Pattern.quote(File.separator));

	private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");

	/** A UNC drive, {@code \\server\share}. */
	private static final Pattern UNC = Pattern.compile("\\\\\\\\[^\\\\]+\\\\[^\\\\]+");

	private PythonFiles() {
	}

	/**
	 * The text of a UTF-8 file, as Python's {@code Path.read_text(encoding="utf-8")}
	 * reads it: a byte order mark kept, and each line ending in a line feed, whether it
	 * ended in {@code \r\n} or {@code \r}.
	 * @param file the file, as given
	 * @return its text
	 * @throws PythonFileException when the file cannot be read, or is not UTF-8
	 */
	static String readText(String file) {
		byte[] raw = bytes(file);
		Optional<TextDecoding.DecodeError> error = Utf8.firstError(raw);
		if (error.isPresent()) {
			throw new PythonFileException(error.get().describe(Utf8.CODEC));
		}
		return new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * The JSON a file holds, as Python's {@code json.loads(Path.read_text())} reads it.
	 * @param file the file, as given
	 * @return its value
	 * @throws PythonFileException when the file cannot be read, is not UTF-8, is not JSON
	 * or nests deeper than it is read
	 */
	static JsonNode readJson(String file) {
		String text = readText(file);
		try {
			return PythonJsonReader.read(text);
		}
		catch (JsonDecodeException | JsonTooDeepException ex) {
			throw new PythonFileException(String.valueOf(ex.getMessage()), ex);
		}
	}

	/**
	 * A file named as {@code str(Path(file))} names it: the system's separators, no
	 * {@code .} between them, none doubled or at the end, and {@code .} for no name.
	 * @param file the file, as given
	 * @return its name
	 */
	static String name(String file) {
		Split split = split(file);
		String named = split.anchor() + String.join(File.separator, split.parts());
		return named.isEmpty() ? "." : named;
	}

	/**
	 * A file's last name, as {@code Path(file).name} gives it.
	 * @param file the file, as given
	 * @return its last name; empty for none, as for {@code .}
	 */
	static String fileName(String file) {
		List<String> parts = split(file).parts();
		return parts.isEmpty() ? "" : parts.getLast();
	}

	private static byte[] bytes(String file) {
		String name = PythonText.repr(name(file));
		Path path;
		try {
			path = Path.of(file);
		}
		catch (InvalidPathException ex) {
			throw new PythonFileException(
					(file.indexOf('\0') >= 0) ? "embedded null byte" : "[Errno 22] Invalid argument: " + name, ex);
		}
		if (Files.isDirectory(path)) {
			// Windows' open() is refused a folder; elsewhere it opens and then fails
			throw new PythonFileException(
					WINDOWS ? "[Errno 13] Permission denied: " + name : "[Errno 21] Is a directory: " + name);
		}
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException ex) {
			throw new PythonFileException(osError(ex, name), ex);
		}
	}

	// an OSError's str(): its errno, its strerror and the file's repr
	private static String osError(IOException ex, String name) {
		return switch (ex) {
			case NoSuchFileException _ -> "[Errno 2] No such file or directory: " + name;
			case AccessDeniedException _ -> "[Errno 13] Permission denied: " + name;
			default -> String.valueOf(ex.getMessage());
		};
	}

	// the drive and root pathlib keeps, and the names after them: on Windows, a UNC
	// \\server\share is the drive, which a root always follows; on POSIX, two slashes at
	// the start stay two, and any other number is one
	private static Split split(String file) {
		String text = WINDOWS ? file.replace('/', '\\') : file;
		Matcher unc = UNC.matcher(text);
		if (WINDOWS && unc.lookingAt()) {
			return new Split(unc.group() + File.separator, parts(text.substring(unc.end())));
		}
		String drive = (WINDOWS && DRIVE.matcher(text).lookingAt()) ? text.substring(0, 2) : "";
		String rest = text.substring(drive.length());
		String root = "";
		if (rest.startsWith(File.separator)) {
			boolean two = !WINDOWS && rest.startsWith("//") && !rest.startsWith("///");
			root = two ? "//" : File.separator;
		}
		return new Split(drive + root, parts(rest));
	}

	private static List<String> parts(String text) {
		return SEPARATOR.splitAsStream(text).filter((part) -> !part.isEmpty() && !".".equals(part)).toList();
	}

	/**
	 * A file's name in pathlib's parts.
	 *
	 * @param anchor its drive and root, if any
	 * @param parts the names after them, without {@code .}
	 */
	private record Split(String anchor, List<String> parts) {

	}

}
