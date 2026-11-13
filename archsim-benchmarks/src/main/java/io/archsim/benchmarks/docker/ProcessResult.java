package io.archsim.benchmarks.docker;

/**
 * What a command did: its exit code, and what it printed out and as errors.
 *
 * @param exitCode its exit code
 * @param output what it printed out
 * @param errors what it printed as errors
 */
public record ProcessResult(int exitCode, String output, String errors) {

	/**
	 * Whether the command succeeded.
	 * @return whether it exited with 0
	 */
	public boolean succeeded() {
		return this.exitCode == 0;
	}

}
