package io.archsim.benchmarks.history;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The committed {@code tree-ids.tsv} against this clone's tags.
 */
class CommittedTreeIdsTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	// an engine is known by the tree it was recorded with; its tag holds
	// that tree as the 13 Nov rewrite left it
	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource({ "0.16.0, engine-0.16.0", "0.14.0, bench-baseline-0.14.0" })
	void current_anEnginesRecordedTree_isItsTagsTreeToday(String version, String tag) {
		Map<String, String> tagged = new GitRepository(LAYOUT.root()).folderTrees(List.of(tag), TreeIds.ENGINE_FOLDER);
		assumeThat(tagged).as("the tag %s is in this clone", tag).containsKey(tag);
		String recorded = Objects.requireNonNull(Engines.read(LAYOUT.engines()).engineTrees().get(version));

		String current = TreeIds.read(LAYOUT.treeIds()).current(recorded);

		assertThat(current).isEqualTo(tagged.get(tag));
	}

}
