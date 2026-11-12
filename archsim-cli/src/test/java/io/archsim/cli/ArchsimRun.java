package io.archsim.cli;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * A run of the {@code archsim} command in the test's own JVM: its exit code and what it
 * printed.
 *
 * @param exitCode its exit code
 * @param out what it printed on standard output
 * @param err what it printed on standard error
 */
record ArchsimRun(int exitCode, String out, String err) {

	/**
	 * Runs the command with these arguments.
	 * @param args the arguments, the command's name first
	 * @return what it did
	 */
	static ArchsimRun of(String... args) {
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		int exitCode = Archsim.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
		return new ArchsimRun(exitCode, out.toString(), err.toString());
	}

}
