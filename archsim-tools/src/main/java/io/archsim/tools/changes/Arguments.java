package io.archsim.tools.changes;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The arguments of {@link ChangedParts}, read as {@code argparse} read them:
 * {@code --all}, or {@code --base BASE --head HEAD}, and {@code --github-output FILE},
 * each value also as {@code --name=VALUE}. They use the JDK alone, as the command does.
 *
 * @param all whether every job runs, as for a run started by hand
 * @param base the base; empty when not given
 * @param head the head
 * @param githubOutput GitHub's output file, when given
 * @param help whether {@code -h} or {@code --help} asked for the usage
 */
record Arguments(boolean all, String base, Optional<String> head, Optional<Path> githubOutput, boolean help) {

	private static final String ALL = "--all";

	private static final Set<String> VALUED = Set.of("--base", "--head", "--github-output");

	/**
	 * The arguments a command line gave.
	 * @param arguments the command line's arguments
	 * @return the arguments
	 * @throws IllegalArgumentException when one is unknown or lacks its value, or neither
	 * {@code --all} nor {@code --head} is given, in argparse's words
	 */
	static Arguments of(List<String> arguments) {
		if (arguments.contains("-h") || arguments.contains("--help")) {
			return new Arguments(false, "", Optional.empty(), Optional.empty(), true);
		}
		Map<String, String> values = values(arguments);
		boolean all = arguments.contains(ALL);
		Optional<String> head = Optional.ofNullable(values.get("--head"));
		if (!all && head.isEmpty()) {
			throw new IllegalArgumentException("give --all, or --base and --head");
		}
		return new Arguments(all, values.getOrDefault("--base", ""), head,
				Optional.ofNullable(values.get("--github-output")).map(Path::of), false);
	}

	// each option's value, the last one given
	private static Map<String, String> values(List<String> arguments) {
		Map<String, String> values = new HashMap<>();
		int index = 0;
		while (index < arguments.size()) {
			String argument = arguments.get(index);
			if (ALL.equals(argument)) {
				index++;
				continue;
			}
			boolean joined = argument.contains("=");
			String name = joined ? argument.substring(0, argument.indexOf('=')) : argument;
			if (!VALUED.contains(name)) {
				throw new IllegalArgumentException("unrecognized arguments: " + argument);
			}
			values.put(name, value(arguments, index, name));
			index += joined ? 1 : 2;
		}
		return values;
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

}
