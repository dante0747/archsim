package io.archsim.benchmarks.history;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a history rewrite made of each commit: a file of lines {@code old new}, the
 * commit's ID before the rewrite and after it, under a header line.
 */
public final class CommitMap {

	/**
	 * A header of two words, such as {@code old new} or {@code recorded current}, the
	 * first with a letter no commit ID has; then a line of two IDs, spaces or a tab
	 * between them.
	 */
	private static final PairFile.Format FORMAT = new PairFile.Format(
			Pattern.compile("\\s*[a-z]*[g-z][a-z]*\\s+[a-z]+\\s*"), "old new",
			Pattern.compile("\\s*(\\S+)\\s+(\\S+)\\s*"), "IDs");

	private final Map<String, String> rewritten;

	private CommitMap(Map<String, String> rewritten) {
		this.rewritten = Map.copyOf(rewritten);
	}

	/**
	 * The map in a file.
	 * @param file the file: a header line, then one {@code old new} pair per line,
	 * separated by spaces or a tab
	 * @return the map
	 * @throws IllegalArgumentException when the file has no header, or a line is not two
	 * IDs or names a commit twice
	 */
	public static CommitMap read(Path file) {
		return new CommitMap(PairFile.read(file, FORMAT));
	}

	/**
	 * A commit after the rewrite.
	 * @param old its ID before the rewrite
	 * @return its ID after; empty when the map does not name it, or names the all-zero ID
	 * git-filter-repo gives a commit it pruned
	 */
	Optional<String> current(String old) {
		return Optional.ofNullable(this.rewritten.get(old))
			.filter((id) -> !id.chars().allMatch((digit) -> digit == '0'));
	}

}
