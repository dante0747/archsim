package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import io.archsim.benchmarks.Processes;
import io.archsim.benchmarks.ScratchFolder;

/**
 * Commands run on this machine. A command reads an empty input and writes what it prints
 * to files, never to pipes, which could fill while the other is read.
 */
public final class SystemProcesses implements ProcessRunner {

	@Override
	public ProcessResult run(List<String> command, Map<String, String> environment) {
		try (ScratchFolder scratch = ScratchFolder.create("archsim-process")) {
			Files.writeString(scratch.input(), "", StandardCharsets.UTF_8);
			ProcessBuilder builder = new ProcessBuilder(command).redirectInput(scratch.input().toFile())
				.redirectOutput(scratch.output().toFile())
				.redirectError(scratch.errors().toFile());
			builder.environment().putAll(environment);
			int exitCode = Processes.waitFor(builder.start(), command);
			// bytes that are not UTF-8 are replaced, so a command's reason is never lost
			return new ProcessResult(exitCode, new String(Files.readAllBytes(scratch.output()), StandardCharsets.UTF_8),
					new String(Files.readAllBytes(scratch.errors()), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
