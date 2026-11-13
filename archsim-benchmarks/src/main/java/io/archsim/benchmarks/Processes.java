package io.archsim.benchmarks;

import java.util.List;

/**
 * Waiting for a command the harness started: git, Docker.
 */
public final class Processes {

	private Processes() {
	}

	/**
	 * Waits for a command to end. An interruption stops it and waits for it to end, so
	 * that the files it held can be deleted.
	 * @param process the command's process
	 * @param command the command, named in the exception when it is interrupted
	 * @return its exit code
	 * @throws IllegalStateException when interrupted, the thread's interrupt kept
	 */
	public static int waitFor(Process process, List<String> command) {
		try {
			return process.waitFor();
		}
		catch (InterruptedException ex) {
			process.destroyForcibly().onExit().join();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while running " + String.join(" ", command), ex);
		}
	}

}
