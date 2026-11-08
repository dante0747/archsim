package io.archsim.engine.compare;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.engine.report.ReportProvenance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why two runs may differ other than by their designs: a result digest names a result
 * within one engine (ADR-0050), so a Python run beside a Java run is named for what it
 * is, as two engine versions are, and not taken for another build.
 */
class ComparisonWarningsTests {

	private static final ReportProvenance PYTHON = new ReportProvenance("0.16.0", 7L, "a1", null);

	private static final ReportProvenance JAVA = new ReportProvenance("0.16.0", 7L, "b2", "25.0.3");

	private static final String OTHER_ENGINES = "The runs were simulated by different engines, one by the Python "
			+ "engine and one by the Java engine, so their result digests need not match even for the same inputs "
			+ "and seed.";

	@Test
	void warnings_aPythonRunAndAJavaRunOfTheSameInputsAndSeed_nameTheEnginesNotOtherBuilds() {
		List<String> warnings = ComparisonText.warnings(facts(PYTHON, JAVA));

		assertThat(warnings).containsExactly(OTHER_ENGINES);
	}

	@Test
	void warnings_aPythonRunAndAJavaRunOfOtherSeeds_nameTheSeedsAndTheEngines() {
		ReportProvenance otherSeed = new ReportProvenance("0.16.0", 8L, "b2", "25.0.3");

		List<String> warnings = ComparisonText.warnings(facts(PYTHON, otherSeed));

		assertThat(warnings).containsExactly(
				"The runs use different random seeds, so small differences may be random variation.", OTHER_ENGINES);
	}

	@Test
	void warnings_twoJavaRunsOfTheSameInputsWithOtherResults_nameOtherBuilds() {
		ReportProvenance other = new ReportProvenance("0.16.0", 7L, "c3", "25.0.3");

		List<String> warnings = ComparisonText.warnings(facts(JAVA, other));

		assertThat(warnings).containsExactly(
				"Inputs are identical but results differ; this indicates different engine builds or platforms.");
	}

	private static ComparisonText.Facts facts(ReportProvenance first, ReportProvenance second) {
		return new ComparisonText.Facts(new Labels("A", "B"), List.of(), Map.of(), List.of(), null, null, first,
				second);
	}

}
