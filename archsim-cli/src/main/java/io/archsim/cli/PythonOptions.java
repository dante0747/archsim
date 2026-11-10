package io.archsim.cli;

import org.jspecify.annotations.Nullable;

/**
 * Options as the Python CLI tested them, by their truth ({@code if args.out:}): an option
 * given empty, such as {@code -o ""}, is not given.
 */
final class PythonOptions {

	private PythonOptions() {
	}

	/**
	 * An option's value, when Python would have used it.
	 * @param value the value given, if any
	 * @return the value; {@code null} when none or an empty one was given
	 */
	static @Nullable String given(@Nullable String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

}
