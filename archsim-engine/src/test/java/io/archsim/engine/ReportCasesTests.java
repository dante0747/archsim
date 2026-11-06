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
 * The report functions on the cases the golden files do not reach: no objectives, a run
 * without the figures to judge it, compact run summaries, a sweep with no completed step
 * or a latency knee, a run too short to judge, a part whose figures stop its formulas
 * early, a run's confidence weighed by utilization or with its bottleneck found by load,
 * its cost without base-load buckets, an offered rate, a throughput or measured replicas,
 * or with a base load of zero, a comparison of identical inputs or of a bottleneck that
 * moved, went or came, a check of breaking points, without a baseline or objectives, or
 * of design text with markup, and more. Each case ({@code report/cases.json}, which
 * {@code scripts/export_report_cases.py} writes from the frozen engine) is a call with
 * the Python engine's output, checked as the golden files are.
 */
@DisplayName("The report functions match the frozen Python engine where the golden files do not reach")
class ReportCasesTests {

	private static final String CASES = "report/cases.json";

	private static final List<GoldenEntry> ENTRIES = CaseFiles.entries(CASES);

	@TestFactory
	Stream<DynamicTest> reportFunction_everyCase_givesTheFrozenEnginesOutput() {
		return ENTRIES.stream()
			.map((entry) -> DynamicTest.dynamicTest(entry.function() + " " + entry.pointer(),
					() -> Parity.check(entry, EngineFunctions::call)));
	}

	@Test
	void cases_everyFunctionTheyCall_isPorted() {
		assertThat(ENTRIES).extracting(GoldenEntry::fn).allMatch(EngineFunctions.names()::contains);
	}

}
