package io.archsim.benchmarks.report;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knee range's page on copies of the committed fit.
 */
class KneeRangePageTests {

	// a fold whose fit had too few runs gives the run no range in it
	@Test
	void text_aRunWithoutAFoldsRange_showsADash() {
		ObjectNode fit = (ObjectNode) ResultFiles.read(RepositoryLayout.current().kneeRange()).orElseThrow().deepCopy();
		ObjectNode run = (ObjectNode) fit.get("checked")
			.valueStream()
			.filter((checked) -> checked.get("source").stringValue().equals("pg-mixed/run-8"))
			.findFirst()
			.orElseThrow();
		((ObjectNode) run.get("ranges")).remove("leaveExperimentOut");

		String text = KneeRangePage.text(fit);

		assertThat(text.lines().filter((line) -> line.startsWith("| pg-mixed | `pg-mixed/run-8/` |"))).singleElement()
			.satisfies((line) -> assertThat(line).endsWith(" | - |"));
	}

}
