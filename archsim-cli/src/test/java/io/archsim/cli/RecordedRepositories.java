package io.archsim.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;

/**
 * The git repository a recorded {@code check} case runs in, made as
 * {@code scripts/export_cli_cases.py} made it: one commit of the files the case names,
 * and the case's design beside them.
 */
final class RecordedRepositories {

	/** The longest a git command of the fixture may take. */
	private static final long GIT_SECONDS = 60;

	private RecordedRepositories() {
	}

	/**
	 * Makes a case's repository.
	 * @param spec the case's {@code repo}: the files committed (in base64) and the
	 * design's file
	 * @param folder where the repository is made
	 * @return the design's file in it, its folder made
	 * @throws IOException when a file cannot be written or git fails
	 */
	static Path create(JsonNode spec, Path folder) throws IOException {
		Files.createDirectories(folder);
		git(folder, "init", "-q");
		git(folder, "config", "user.email", "test@example.com");
		git(folder, "config", "user.name", "Test");
		git(folder, "config", "core.autocrlf", "false");
		// a fixture, whatever the developer's own git signs commits with
		git(folder, "config", "commit.gpgsign", "false");
		for (Map.Entry<String, JsonNode> file : spec.get("files").properties()) {
			Path committed = folder.resolve(file.getKey());
			Files.createDirectories(Objects.requireNonNull(committed.getParent()));
			Files.write(committed, Base64.getDecoder().decode(file.getValue().stringValue()));
		}
		git(folder, "add", ".");
		git(folder, "commit", "-q", "-m", "base");
		Path design = folder.resolve(spec.get("file").stringValue());
		Files.createDirectories(Objects.requireNonNull(design.getParent()));
		return design;
	}

	// what git says goes to a file, so that the deadline holds while it runs
	private static void git(Path folder, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		Path log = Files.createTempFile("archsim-git", ".log");
		try {
			Process git = new ProcessBuilder(command).directory(folder.toFile())
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();
			boolean done = git.waitFor(GIT_SECONDS, TimeUnit.SECONDS);
			if (!done) {
				git.destroyForcibly();
			}
			if (!done || git.exitValue() != 0) {
				throw new IOException(String.join(" ", command) + " failed: "
						+ new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException(String.join(" ", command) + " was interrupted", ex);
		}
		finally {
			Files.deleteIfExists(log);
		}
	}

}
