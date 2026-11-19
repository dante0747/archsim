package io.archsim.benchmarks.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words of the page's "Against simple estimates" block ({@code baseline-texts.json}).
 */
class BaselineTextsTests {

	private static final BaselineTexts TEXTS = BaselineTexts.standard();

	@ParameterizedTest
	@CsvSource({ "simulator, Simulator", "bottleneckArithmetic, Bottleneck arithmetic",
			"analyticEstimate, Engine's analytic estimate", "utilisationExtrapolation, Utilisation extrapolation" })
	void name_theSimulatorAndEachEstimate_isItsColumnsTitle(String estimate, String title) {
		assertThat(TEXTS.name(estimate)).isEqualTo(title);
	}

	// the intro explains each estimate under the name its column has
	@ParameterizedTest
	@CsvSource({ "bottleneckArithmetic", "analyticEstimate", "utilisationExtrapolation" })
	void intro_eachEstimate_isExplainedUnderItsColumnsTitle(String estimate) {
		assertThat(TEXTS.intro()).contains("- **" + TEXTS.name(estimate) + ".** ");
	}

	@Test
	void computed_theDatesAndEngines_areNamedInTheSentence() {
		String sentence = TEXTS.computed("2026-11-19", "1.5.0");

		assertThat(sentence).contains("(on 2026-11-19; the analytic estimate by engine 1.5.0)");
	}

	@Test
	void rules_theDayTheyWereFixed_saysWhichRunsTheyWereWrittenAfter() {
		String sentence = TEXTS.rules("2026-11-19");

		assertThat(sentence).isEqualTo("Their rules were fixed on 2026-11-19, with the results of every run "
				+ "calibrated before that day in view: for those runs the estimates check what the simulation adds "
				+ "and are not predictions.");
	}

	@Test
	void later_theRunsCalibratedAfterTheRules_saysTheRulesPredateTheirMeasurement() {
		String sentence = TEXTS.later("kafka and chain");

		assertThat(sentence).startsWith("The runs shown of kafka and chain were calibrated after it: for them the "
				+ "rules predate the measurement");
	}

	// the cache stacks' arithmetic reads one figure that is not a calibrated input
	@Test
	void intro_theBottleneckArithmetic_namesTheCountedMissShareItAlsoReads() {
		assertThat(TEXTS.intro())
			.contains("a database behind a cache is sent the share of lookups that missed in the top calibration pass");
	}

	@Test
	void overloaded_anExperimentAndTheEstimatesSummary_quotesTheSummary() {
		String sentence = TEXTS.overloaded("kafka", "Overloaded at 2,050 req/s.");

		assertThat(sentence).startsWith("At kafka's knee").contains("(\"Overloaded at 2,050 req/s.\")");
	}

	@Test
	void missing_theExperimentsWithoutBaselines_namesThemAndTheCommand() {
		String sentence = TEXTS.missing("pg-mixed and kafka");

		assertThat(sentence).isEqualTo("No baselines yet for pg-mixed and kafka: run "
				+ "`./gradlew :archsim-benchmarks:run --args=\"baselines\"`.");
	}

}
