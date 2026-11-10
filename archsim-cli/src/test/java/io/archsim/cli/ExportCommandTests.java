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

import io.archsim.cli.RecordedCases.Ran;
import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.export.ExportFormat;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim export} against the frozen engine's CLI: each case of
 * {@code export-cases.json.gz} ({@code scripts/export_cli_cases.py}) exits with the code
 * Python's did and prints, or writes, what it did, word for word; where Python crashed,
 * the departure.
 */
class ExportCommandTests {

	@TestFactory
	Stream<DynamicTest> export_eachRecordedCase_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return Replays.of("export", folder);
	}

	@TestFactory
	Stream<DynamicTest> export_eachUsageErrorRecorded_exitsWithCode2(@TempDir Path folder) {
		return Replays.usageErrors("export", folder);
	}

	@TestFactory
	Stream<DynamicTest> export_eachFileRecordedAsUnreadable_isRefusedInOneLine(@TempDir Path folder) {
		return Replays.unreadableFiles("export", folder);
	}

	// Python's export_document raised KeyError on a link to a part that is not there;
	// POST /export exports such a design in Java, and so does the command
	@Test
	void export_aLinkToAnUnknownPart_printsTheExportTheApiGives(@TempDir Path folder) throws IOException {
		JsonNode recorded = RecordedCases.named("export", "anInvalidDesign");
		String exported = new DesignExporter(StandardComponents.registry(), PriceSnapshots.shipped())
			.export(recorded.get("design"), ExportFormat.COMPOSE);

		Ran java = RecordedCases.run(recorded, folder);

		assertThat(List.of(java.exitCode(), java.out(), java.err())).containsExactly(0, exported, "");
	}

}
