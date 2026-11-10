package io.archsim.cli;

import java.io.PrintWriter;

/**
 * Text as the Python CLI wrote it to standard output with {@code sys.stdout.write(text)}:
 * as it is, but for its line ends, which are the console's.
 */
final class TextOutput {

	private TextOutput() {
	}

	/**
	 * Writes text, every line end the console's.
	 * @param out where to write it
	 * @param text the text, its lines ended by {@code \n}
	 */
	static void write(PrintWriter out, String text) {
		out.print(text.replace("\n", System.lineSeparator()));
		out.flush();
	}

}
