package io.archsim.benchmarks.docker;

import java.util.List;
import java.util.Map;

/**
 * Runs a command, Docker's mostly, and waits for it to end, keeping what it printed.
 */
@FunctionalInterface
public interface ProcessRunner {

	/**
	 * Runs a command and waits for it to end.
	 * @param command the command and its arguments
	 * @param environment variables set over the harness's own
	 * @param input what the command reads: a script for a shell, or nothing
	 * @return its exit code and what it printed
	 */
	ProcessResult run(List<String> command, Map<String, String> environment, String input);

}
