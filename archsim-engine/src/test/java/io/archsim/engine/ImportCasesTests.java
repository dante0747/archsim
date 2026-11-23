package io.archsim.engine;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.parity.ImportWordingDepartures;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.Parity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docker-compose and Kubernetes importers on what the frozen engine imported for
 * {@code scripts/export_import_cases.py}: its fixtures, every template's compose file and
 * manifests as the exports write them, what its tests imported (tools and look-alike
 * images, infrastructure images by their full name, entry points, placeholder requests,
 * autoscalers, cache misses, memory limits, ArchSim's own parts written badly), YAML as
 * PyYAML reads it, and the refusals the importers raise themselves. Each case
 * ({@code imports/cases.json}) is checked as the golden files are, its words for what the
 * autoscaler reads taken as the Java words them ({@link ImportWordingDepartures}).
 */
@DisplayName("The YAML importers match the frozen Python engine")
class ImportCasesTests {

	private static final String CASES = "imports/cases.json";

	private static final List<GoldenEntry> ENTRIES = CaseFiles.entries(CASES);

	@TestFactory
	Stream<DynamicTest> importer_everyCase_givesTheFrozenEnginesDesignAndReport() {
		return ENTRIES.stream()
			.map((entry) -> DynamicTest.dynamicTest(entry.pointer(),
					() -> Parity.check(asJavaWordsIt(entry), ImportFunctions::call)));
	}

	@Test
	void cases_everyFunctionTheyCall_isPorted() {
		assertThat(ENTRIES).extracting(GoldenEntry::fn).allMatch(ImportFunctions.names()::contains);
	}

	// a refusal has no report to word
	private static GoldenEntry asJavaWordsIt(GoldenEntry entry) {
		if (entry.raised()) {
			return entry;
		}
		JsonNode worded = ImportWordingDepartures.asJavaWordsIt(entry.expectedOutput());
		return new GoldenEntry(entry.file(), entry.pointer(), entry.fn(), entry.input(), worded, null);
	}

}
