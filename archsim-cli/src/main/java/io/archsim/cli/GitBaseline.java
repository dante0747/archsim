package io.archsim.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.text.PythonText;

/**
 * The baseline {@code check --baseline-ref} reads: the design's file as it is at a git
 * ref, read through the git on the {@code PATH} with the commands the Python CLI ran, and
 * its bytes read as {@code json.loads(bytes)} read them ({@link PythonJsonBytes}). Each
 * command may take {@link #DEADLINE}; Python waited without end.
 */
final class GitBaseline {

	/** The longest one git command may take. */
	static final Duration DEADLINE = Duration.ofSeconds(60);

	/** What the system says when a program cannot be started: {@code error=2, ...}. */
	private static final Pattern START_ERROR = Pattern.compile("error=(\\d+)");

	private static final String NOT_FOUND = "2";

	private final List<String> git;

	private final Duration deadline;

	/**
	 * The baseline read through the git on the {@code PATH}.
	 */
	GitBaseline() {
		this(List.of("git"), DEADLINE);
	}

	/**
	 * The baseline read through a git of its own, under a deadline of its own.
	 * @param git the command run as git, its arguments after it
	 * @param deadline the longest one command may take
	 */
	GitBaseline(List<String> git, Duration deadline) {
		this.git = List.copyOf(git);
		this.deadline = deadline;
	}

	/**
	 * The design's file as it is at a ref, as the Python CLI's {@code _at_ref} read it.
	 * @param ref the ref, such as {@code origin/main}
	 * @param file the design's file, as given
	 * @return its JSON; {@code null} when the ref's commit holds no such file, a new
	 * design
	 * @throws GitBaselineException when the file is not in a repository, the ref cannot
	 * be read, git cannot show the file, cannot be run or takes too long
	 * @throws PythonFileException when the file at the ref is not text or not JSON
	 */
	@Nullable JsonNode at(String ref, String file) {
		Path design = real(Path.of(file));
		Answer top = run(parent(design), "rev-parse", "--show-toplevel");
		if (top.exitCode() != 0) {
			throw new GitBaselineException(file + " is not in a git repository, so --baseline-ref cannot read it");
		}
		Path root = real(Path.of(top.text().strip()));
		if (!design.startsWith(root)) {
			// pathlib's relative_to
			throw new GitBaselineException(PythonText.repr(design.toString()) + " is not in the subpath of "
					+ PythonText.repr(root.toString()));
		}
		String relative = root.relativize(design).toString().replace(File.separatorChar, '/');
		Answer commit = run(root, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
		if (commit.exitCode() != 0) {
			throw new GitBaselineException(("git ref '%s' cannot be read: check the name, and fetch it in a shallow "
					+ "checkout (actions/checkout with fetch-depth: 0)")
				.formatted(ref));
		}
		String blob = commit.text().strip() + ":" + relative;
		if (run(root, "cat-file", "-e", blob).exitCode() != 0) {
			return null;
		}
		Answer shown = run(root, "cat-file", "blob", blob);
		if (shown.exitCode() != 0) {
			throw new GitBaselineException("cannot read %s at %s: %s".formatted(relative, ref, shown.errors().strip()));
		}
		return PythonJsonBytes.read(shown.output());
	}

	private Answer run(Path folder, String... args) {
		List<String> command = new ArrayList<>(this.git);
		command.addAll(List.of(args));
		Process process = start(command, folder);
		try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<byte[]> output = readers.submit(() -> readAll(process.getInputStream()));
			Future<byte[]> errors = readers.submit(() -> readAll(process.getErrorStream()));
			try {
				if (!process.waitFor(this.deadline.toMillis(), TimeUnit.MILLISECONDS)) {
					throw new GitBaselineException(
							"git %s took more than %d s".formatted(args[0], this.deadline.toSeconds()));
				}
				return new Answer(process.exitValue(), output.get(), errors.get());
			}
			finally {
				// before the readers close, which wait for the pipes git and anything it
				// started hold
				stop(process);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitBaselineException("git " + args[0] + " was interrupted", ex);
		}
		catch (ExecutionException ex) {
			throw new GitBaselineException("cannot read what git " + args[0] + " said", ex);
		}
	}

	private Process start(List<String> command, Path folder) {
		try {
			Process process = new ProcessBuilder(command).directory(folder.toFile()).start();
			// as subprocess.run without input: git reads nothing
			process.getOutputStream().close();
			return process;
		}
		catch (IOException ex) {
			throw new GitBaselineException(notStarted(ex), ex);
		}
	}

	// git, and what it started, when the wait ended before them
	private static void stop(Process process) {
		if (process.isAlive()) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}
	}

	// the FileNotFoundError Python's subprocess raised for a git that is not installed
	private String notStarted(IOException ex) {
		Matcher error = START_ERROR.matcher(String.valueOf(ex.getMessage()));
		if (!error.find() || !NOT_FOUND.equals(error.group(1))) {
			return String.valueOf(ex.getMessage());
		}
		return PythonFiles.WINDOWS ? "[WinError 2] The system cannot find the file specified"
				: "[Errno 2] No such file or directory: " + PythonText.repr(this.git.getFirst());
	}

	private static byte[] readAll(InputStream stream) throws IOException {
		try (stream) {
			return stream.readAllBytes();
		}
	}

	// Path.resolve() as pathlib has it: absolute, normal, links followed where they are
	private static Path real(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		try {
			return absolute.toRealPath();
		}
		catch (IOException ex) {
			return absolute;
		}
	}

	private static Path parent(Path design) {
		Path parent = design.getParent();
		return (parent != null) ? parent : design;
	}

	/**
	 * What a git command answered.
	 */
	private static final class Answer {

		private final int exitCode;

		private final byte[] output;

		private final byte[] errors;

		Answer(int exitCode, byte[] output, byte[] errors) {
			this.exitCode = exitCode;
			this.output = output;
			this.errors = errors;
		}

		int exitCode() {
			return this.exitCode;
		}

		byte[] output() {
			return this.output;
		}

		// what it printed, as Python's bytes.decode() read git's UTF-8
		String text() {
			return new String(this.output, StandardCharsets.UTF_8);
		}

		// what it complained of, as bytes.decode("utf-8", "replace") read it
		String errors() {
			return new String(this.errors, StandardCharsets.UTF_8);
		}

	}

}
