package io.archsim.engine;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.Parity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exports on the designs the golden files do not reach: managed services, a type the
 * registry does not know, technologies without an image, a third party whose technology
 * is empty, settings of the wrong kind, a design that does not auto-size, ids that clash
 * as DNS or Mermaid names, strings YAML would read as something else, numbers JavaScript
 * writes differently, a client's settings with nested lists and empty maps, text outside
 * ASCII, and a link to a part the design does not have. Each case
 * ({@code export/cases.json}, which {@code scripts/export_export_cases.py}, since
 * deleted, recorded from the frozen engine) is a call with the Python engine's output,
 * checked as the golden files are.
 */
@DisplayName("The exports match the frozen Python engine where the golden files do not reach")
class ExportCasesTests {

	private static final String CASES = "export/cases.json";

	private static final List<GoldenEntry> ENTRIES = CaseFiles.entries(CASES);

	@TestFactory
	Stream<DynamicTest> exportFunction_everyCase_givesTheFrozenEnginesOutput() {
		return ENTRIES.stream()
			.map((entry) -> DynamicTest.dynamicTest(entry.pointer(), () -> Parity.check(entry, EngineFunctions::call)));
	}

	@Test
	void cases_everyFunctionTheyCall_isPorted() {
		assertThat(ENTRIES).extracting(GoldenEntry::fn).allMatch(EngineFunctions.names()::contains);
	}

}
