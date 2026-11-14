package io.archsim.tools.commits;

import java.util.List;

/**
 * The check's arguments, read as {@code argparse} read them: {@code --file FILE} or
 * {@code --range RANGE}, one of the two and not both, each also as {@code --file=FILE}.
 * They use the JDK alone, as the check does.
 *
 * @param mode what to check
 * @param value the message's file or the revision range; empty for the usage
 */
record Arguments(Mode mode, String value) {

	private static final String FILE = "--file";

	private static final String RANGE = "--range";

	/**
	 * The arguments a command line gave.
	 * @param arguments the command line's arguments
	 * @return the arguments
	 * @throws IllegalArgumentException when one is unknown, lacks its value, or both or
	 * neither of {@code --file} and {@code --range} are given, in argparse's words
	 */
	static Arguments of(List<String> arguments) {
		if (arguments.contains("-h") || arguments.contains("--help")) {
			return new Arguments(Mode.HELP, "");
		}
		Arguments given = null;
		int index = 0;
		while (index < arguments.size()) {
			String argument = arguments.get(index);
			boolean joined = argument.contains("=");
			String name = joined ? argument.substring(0, argument.indexOf('=')) : argument;
			Mode mode = mode(name, argument);
			if (given != null && given.mode() != mode) {
				throw new IllegalArgumentException(
						"argument " + name + ": not allowed with argument " + ((mode == Mode.FILE) ? RANGE : FILE));
			}
			given = new Arguments(mode, value(arguments, index, name));
			index += joined ? 1 : 2;
		}
		if (given == null) {
			throw new IllegalArgumentException("one of the arguments --file --range is required");
		}
		return given;
	}

	private static Mode mode(String name, String argument) {
		if (FILE.equals(name)) {
			return Mode.FILE;
		}
		if (RANGE.equals(name)) {
			return Mode.RANGE;
		}
		throw new IllegalArgumentException("unrecognized arguments: " + argument);
	}

	// an option's value: after its '=', or the next argument
	private static String value(List<String> arguments, int index, String name) {
		String argument = arguments.get(index);
		if (argument.contains("=")) {
			return argument.substring(argument.indexOf('=') + 1);
		}
		if (index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
			throw new IllegalArgumentException("argument " + name + ": expected one argument");
		}
		return arguments.get(index + 1);
	}

	/**
	 * What the arguments ask for.
	 */
	enum Mode {

		/** One message, in a file. */
		FILE,

		/** Every commit in a revision range. */
		RANGE,

		/** The usage. */
		HELP

	}

}
