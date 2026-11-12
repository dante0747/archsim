package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.cli.RecordedCases.Ran;
import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim analyze} against the frozen engine's CLI: each case of
 * {@code analyze-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did and prints what it printed, word for word;
 * where Python crashed, the departure.
 */
class AnalyzeCommandTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TestFactory
	Stream<DynamicTest> analyze_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("analyze", folder);
	}

	// Python crashed with a traceback, exit code 1
	@TestFactory
	Stream<DynamicTest> analyze_eachFileRecordedAsUnreadable_isRefusedInOneLine(@TempDir Path folder) {
		return Replays.unreadableFiles("analyze", folder);
	}

	// Python's analyze_document raised KeyError on a link to a part that is not there;
	// POST /analyze analyzes such a design in Java, and so does the command
	@Test
	void analyze_aLinkToAnUnknownPart_printsTheAnalysisTheApiGives(@TempDir Path folder) throws IOException {
		JsonNode recorded = RecordedCases.named("analyze", "anInvalidDesign");
		JsonNode analysis = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped())
			.analyze(recorded.get("design"), null)
			.toJson();

		Ran java = RecordedCases.run(recorded, folder);

		assertThat(List.of(java.exitCode(), java.err())).containsExactly(0, "");
		assertThat(JsonDiff.differences(analysis, JSON.readTree(java.out()))).isEmpty();
	}

}
