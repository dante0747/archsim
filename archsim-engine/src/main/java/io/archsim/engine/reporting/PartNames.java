package io.archsim.engine.reporting;

import java.util.List;

/** Parts as a report's model notes name them: the first five, and how many more. */
final class PartNames {

	/** At most this many parts are named in a note. */
	private static final int NAMED = 5;

	private PartNames() {
	}

	/**
	 * Parts as a note names them.
	 * @param names the parts' names, at least one
	 * @return such as "Part 1, Part 2, Part 3, Part 4, Part 5 and 2 more"
	 */
	static String shown(List<String> names) {
		String shown = String.join(", ", names.subList(0, Math.min(NAMED, names.size())));
		if (names.size() > NAMED) {
			shown += " and " + (names.size() - NAMED) + " more";
		}
		return shown;
	}

}
