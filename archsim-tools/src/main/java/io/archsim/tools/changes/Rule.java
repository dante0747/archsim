package io.archsim.tools.changes;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A path and the jobs a change to it needs. A path ending in {@code /} is a folder and
 * covers everything in it, a path with a {@code *} covers the paths it matches as
 * {@code fnmatch.fnmatchcase} matched them (a {@code *} any characters, slashes included,
 * a {@code ?} one), and any other path is one file.
 *
 * @param path the path
 * @param jobs the jobs
 */
record Rule(String path, Set<Job> jobs) {

	/**
	 * Copies the jobs.
	 */
	Rule {
		jobs = Set.copyOf(jobs);
	}

	/**
	 * Whether the rule covers a path: the same file, anything in the rule's folder, or a
	 * path its pattern matches.
	 * @param changed the path, from the repository's root
	 * @return whether it covers it
	 */
	boolean covers(String changed) {
		if (this.path.contains("*") || this.path.contains("?")) {
			return pattern().matcher(changed).matches();
		}
		return this.path.endsWith("/") ? changed.startsWith(this.path) : changed.equals(this.path);
	}

	private Pattern pattern() {
		StringBuilder regex = new StringBuilder();
		for (char character : this.path.toCharArray()) {
			switch (character) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append('.');
				default -> regex.append(Pattern.quote(String.valueOf(character)));
			}
		}
		return Pattern.compile(regex.toString(), Pattern.DOTALL);
	}

}
