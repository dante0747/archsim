package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.experiments.Engines;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repeatability table: each experiment's mean absolute knee error beside its median
 * and range, and the mean over every run of the held-out and of the tuned-on experiments.
 * Engine 0.15.0 is frozen and lists pg-mixed as tuned on, so java-mixed is held out.
 */
class SummaryBlocksTests {

	// java-mixed's runs miss by +60% and +80%
	@Test
	void repeatability_anExperimentsRuns_showTheirMeanAbsoluteKneeError(@TempDir Path folder) throws IOException {
		Map<String, List<JsonNode>> history = Map.of("java-mixed", List.of(run(0.6), run(0.8)));

		String block = SummaryBlocks.repeatability(history, engines(folder));

		assertThat(block).contains("| +70% | +60% to +80% | 70% |");
	}

	@Test
	void repeatability_runsOfBothSamples_giveEachSamplesMeanAbsoluteKneeError(@TempDir Path folder) throws IOException {
		Map<String, List<JsonNode>> history = Map.of("java-mixed", List.of(run(0.6), run(0.8)), "pg-mixed",
				List.of(run(-0.1), run(0.2)));

		String block = SummaryBlocks.repeatability(history, engines(folder));

		assertThat(block).contains("Mean absolute knee error over every such run, held out: 70% (2 runs).")
			.contains("Mean absolute knee error over every such run, tuned on: 15% (2 runs).");
	}

	@Test
	void repeatability_aSampleOfOneRun_saysOneRun(@TempDir Path folder) throws IOException {
		Map<String, List<JsonNode>> history = Map.of("java-mixed", List.of(run(0.6)));

		String block = SummaryBlocks.repeatability(history, engines(folder));

		assertThat(block).contains("held out: 60% (1 run).");
	}

	// a counted run engine 0.15.0 predicted, without a range
	private static JsonNode run(double error) {
		return JsonNodeFactory.instance.objectNode()
			.put("protocol", "v5")
			.put("machine", "a")
			.put("engineVersion", "0.15.0")
			.put("kneeError", error)
			.putNull("kneeRange");
	}

	private static Engines engines(Path folder) throws IOException {
		return Engines.read(Files.writeString(folder.resolve("engines.json"), """
				[{"version": "0.15.0", "engineTree": "t", "frozenAt": "2026-11-02", "tag": "t",
				  "tunedOn": ["pg-mixed"]}]"""));
	}

}
