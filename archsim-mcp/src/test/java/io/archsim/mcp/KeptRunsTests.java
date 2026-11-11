package io.archsim.mcp;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runs a session keeps, as the Python server kept them: the last twenty, a run kept
 * again the newest.
 */
class KeptRunsTests {

	@Test
	void keep_moreThanTwentyRuns_dropsTheOldest() {
		KeptRuns runs = new KeptRuns();

		IntStream.rangeClosed(1, 21).forEach((run) -> runs.keep("run" + run, JsonNodeFactory.instance.objectNode()));

		assertThat(runs.report("run1")).isEmpty();
		assertThat(runs.report("run2")).isPresent();
		assertThat(runs.ids()).startsWith("run2, run3").endsWith("run20, run21");
	}

	@Test
	void keep_aRunKeptAgain_isTheNewest() {
		KeptRuns runs = new KeptRuns();
		runs.keep("first", JsonNodeFactory.instance.objectNode());
		runs.keep("second", JsonNodeFactory.instance.objectNode());

		runs.keep("first", JsonNodeFactory.instance.objectNode().put("again", true));

		assertThat(runs.ids()).isEqualTo("second, first");
		assertThat(runs.report("first")).hasValueSatisfying((report) -> assertThat(report.has("again")).isTrue());
	}

	@Test
	void ids_noRunKept_isNone() {
		assertThat(new KeptRuns().ids()).isEqualTo("none");
	}

}
