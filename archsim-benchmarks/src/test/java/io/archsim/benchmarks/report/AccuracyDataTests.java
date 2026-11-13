package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.ResultCopies;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accuracy data on the latest runs alone, and on no results at all.
 */
class AccuracyDataTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	@Test
	void of_theLatestRunsAlone_haveNoSpread(@TempDir Path results) throws IOException {
		copyLatestRuns(results);

		JsonNode data = AccuracyData.of(sources(results)).orElseThrow();

		assertThat(data.get("experiments").valueStream().map((experiment) -> experiment.get("spread")))
			.allMatch(PythonValues::isNone);
	}

	@Test
	void of_theLatestRunsAlone_countOneRunEach(@TempDir Path results) throws IOException {
		copyLatestRuns(results);

		JsonNode data = AccuracyData.of(sources(results)).orElseThrow();

		assertThat(data.get("experiments").valueStream().map((experiment) -> experiment.get("runs").intValue()))
			.containsOnly(1);
	}

	// no run under an earlier protocol
	@Test
	void of_theLatestRunsAlone_haveNoRunInSample(@TempDir Path results) throws IOException {
		copyLatestRuns(results);

		JsonNode data = AccuracyData.of(sources(results)).orElseThrow();

		assertThat(data.at("/summary/inSample")).isEqualTo(JsonNodeFactory.instance.arrayNode());
	}

	@Test
	void of_noResults_isEmpty(@TempDir Path folder) {
		assertThat(AccuracyData.of(sources(folder.resolve("none")))).isEmpty();
	}

	@Test
	void text_noResults_isNull(@TempDir Path folder) {
		assertThat(AccuracyData.text(sources(folder.resolve("none")))).isEqualTo("null\n");
	}

	private static void copyLatestRuns(Path results) throws IOException {
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			ResultCopies.copyLatest(LAYOUT.results().resolve(experiment.id()), results.resolve(experiment.id()));
		}
	}

	private static ReportSources sources(Path results) {
		return new ReportSources(results, Engines.read(LAYOUT.engines()), Cpusets.declaredIn(LAYOUT.compose()));
	}

}
