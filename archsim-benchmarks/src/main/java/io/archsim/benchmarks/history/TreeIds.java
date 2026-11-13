package io.archsim.benchmarks.history;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Today's tree of each engine tree {@code benchmarks/engines.json} records:
 * {@code benchmarks/results/tree-ids.tsv}. The 13 Nov 2026 rewrite put the licence in
 * every commit, which changed the trees of the engine's sources; the recorded trees stay
 * as they were (a frozen engine is known by them), and the held-out check compares
 * today's tree with the recorded one through this map.
 */
public final class TreeIds {

	/** The folder whose tree identifies the frozen Python engine. */
	static final String ENGINE_FOLDER = "packages/engine";

	private static final String HEADER = "recorded\tcurrent";

	/** A header, then a line of two trees with a tab between them. */
	private static final PairFile.Format FORMAT = new PairFile.Format(Pattern.compile(Pattern.quote(HEADER)), HEADER,
			Pattern.compile("(\\S+)\t(\\S+)"), "trees");

	private final Map<String, String> current;

	private TreeIds(Map<String, String> current) {
		this.current = new TreeMap<>(current);
	}

	/**
	 * The map in its file.
	 * @param file {@code benchmarks/results/tree-ids.tsv}
	 * @return the map
	 * @throws IllegalArgumentException when the file has no header, or a line is not two
	 * trees separated by a tab or names a tree twice
	 */
	public static TreeIds read(Path file) {
		return new TreeIds(PairFile.read(file, FORMAT));
	}

	/**
	 * Today's tree of each recorded engine tree, found through the history before a
	 * rewrite: the commits there whose engine folder had the recorded tree, each one's
	 * commit after the rewrite, and that commit's engine folder today. Commits without
	 * the engine folder, and commits the rewrite did not keep, are left out.
	 * @param recorded the recorded trees
	 * @param rewrite the history before the rewrite, its commit map and today's
	 * repository
	 * @return the map
	 * @throws IllegalStateException when no commit had a recorded tree, or the commits
	 * that had it came out of the rewrite with no tree or several, or git fails, with
	 * what it said
	 */
	public static TreeIds derive(Collection<String> recorded, Rewrite rewrite) {
		Map<String, String> before = rewrite.before().folderTrees(rewrite.before().commits(), ENGINE_FOLDER);
		Map<String, String> current = new TreeMap<>();
		for (String tree : new TreeSet<>(recorded)) {
			current.put(tree, todaysTree(tree, before, rewrite));
		}
		return new TreeIds(current);
	}

	/**
	 * Today's tree of a recorded one.
	 * @param recorded the recorded tree
	 * @return today's, or the recorded tree when the map does not name it
	 */
	public String current(String recorded) {
		return this.current.getOrDefault(recorded, recorded);
	}

	/**
	 * The file's text: a header, then a line {@code recorded<TAB>current} per tree,
	 * sorted by the recorded tree.
	 * @return the text
	 */
	public String text() {
		StringBuilder text = new StringBuilder(HEADER).append('\n');
		this.current.forEach((recorded, today) -> text.append(recorded).append('\t').append(today).append('\n'));
		return text.toString();
	}

	// the one tree today of the commits whose engine folder had the recorded tree
	private static String todaysTree(String tree, Map<String, String> before, Rewrite rewrite) {
		List<String> commitsWithTree = before.entrySet()
			.stream()
			.filter((commit) -> commit.getValue().equals(tree))
			.map(Map.Entry::getKey)
			.toList();
		if (commitsWithTree.isEmpty()) {
			throw new IllegalStateException("no commit before the rewrite had engine tree " + tree + " in "
					+ ENGINE_FOLDER + ": is the clone the history before the rewrite?");
		}
		List<String> kept = commitsWithTree.stream().map(rewrite.commits()::current).flatMap(Optional::stream).toList();
		Set<String> today = new TreeSet<>(rewrite.now().folderTrees(kept, ENGINE_FOLDER).values());
		if (today.isEmpty()) {
			throw new IllegalStateException("none of the " + commits(commitsWithTree.size()) + " that had engine tree "
					+ tree + " kept an engine folder through the rewrite");
		}
		if (today.size() > 1) {
			throw new IllegalStateException(commits(commitsWithTree.size()) + " that had engine tree " + tree
					+ " came out of the rewrite with " + today.size() + " trees, not one: " + today);
		}
		return today.iterator().next();
	}

	private static String commits(int count) {
		return count + ((count == 1) ? " commit" : " commits");
	}

	/**
	 * A history rewrite: the repository before it, what it made of each commit, and the
	 * repository today.
	 *
	 * @param before the history before the rewrite (a clone of its backup)
	 * @param commits the rewrite's commit map
	 * @param now the repository today
	 */
	public record Rewrite(GitRepository before, CommitMap commits, GitRepository now) {

	}

}
