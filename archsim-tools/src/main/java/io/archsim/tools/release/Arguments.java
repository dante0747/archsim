package io.archsim.tools.release;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The arguments of {@link CheckRelease}: {@code --tag TAG --registry REGISTRY}, and
 * {@code --github-output FILE}. They use the JDK alone, as the command does.
 *
 * @param tag the release's tag, such as {@code v1.0.0}
 * @param registry where the workflow pushes the images, such as {@code ghcr.io/dante0747}
 * @param githubOutput GitHub's output file, when given
 */
record Arguments(String tag, String registry, Optional<Path> githubOutput) {

	private static final String TAG = "--tag";

	private static final String REGISTRY = "--registry";

	private static final String GITHUB_OUTPUT = "--github-output";

	private static final Set<String> NAMES = Set.of(TAG, REGISTRY, GITHUB_OUTPUT);

	/**
	 * The arguments a command line gave, each option followed by its value.
	 * @param arguments the command line's arguments
	 * @return the arguments
	 * @throws IllegalArgumentException when one is unknown or lacks its value, or the tag
	 * or the registry is missing
	 */
	static Arguments of(List<String> arguments) {
		Map<String, String> values = new HashMap<>();
		for (int index = 0; index < arguments.size(); index += 2) {
			String name = arguments.get(index);
			if (!NAMES.contains(name)) {
				throw new IllegalArgumentException("unrecognized arguments: " + name);
			}
			if (index + 1 == arguments.size()) {
				throw new IllegalArgumentException("argument " + name + ": expected one argument");
			}
			values.put(name, arguments.get(index + 1));
		}
		return new Arguments(required(values, TAG), required(values, REGISTRY),
				Optional.ofNullable(values.get(GITHUB_OUTPUT)).map(Path::of));
	}

	private static String required(Map<String, String> values, String name) {
		String value = values.get(name);
		if (value == null) {
			throw new IllegalArgumentException("the following arguments are required: " + name);
		}
		return value;
	}

}
