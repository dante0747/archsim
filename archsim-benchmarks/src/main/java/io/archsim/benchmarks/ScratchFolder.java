package io.archsim.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A folder of a command's input, output and errors, which the command reads and writes as
 * files, never through pipes: a pipe of a few KB fills while the command writes the
 * other, and waiting on one deadlocks. The folder is deleted when closed; a failure to
 * delete it is kept with the exception that ended the run.
 */
public final class ScratchFolder implements AutoCloseable {

	private final Path folder;

	private ScratchFolder(Path folder) {
		this.folder = folder;
	}

	/**
	 * A new folder in the system's temporary folder.
	 * @param prefix what its name starts with
	 * @return the folder
	 * @throws IOException when it cannot be made
	 */
	public static ScratchFolder create(String prefix) throws IOException {
		return new ScratchFolder(Files.createTempDirectory(prefix));
	}

	/**
	 * The command's input.
	 * @return the file
	 */
	public Path input() {
		return this.folder.resolve("stdin.txt");
	}

	/**
	 * What the command prints out.
	 * @return the file
	 */
	public Path output() {
		return this.folder.resolve("stdout.txt");
	}

	/**
	 * What the command prints as errors.
	 * @return the file
	 */
	public Path errors() {
		return this.folder.resolve("stderr.txt");
	}

	@Override
	public void close() throws IOException {
		for (Path file : List.of(input(), output(), errors())) {
			Files.deleteIfExists(file);
		}
		Files.deleteIfExists(this.folder);
	}

}
