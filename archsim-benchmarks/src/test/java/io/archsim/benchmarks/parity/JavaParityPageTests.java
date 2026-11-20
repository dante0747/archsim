package io.archsim.benchmarks.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JavaParityPageTests {

	private static final Speed NO_TEMPLATE_TIMED = Speed.of(List.of());

	@Test
	void render_twoCases_countsEachTestOfTheBarAndTheExactReplays() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(page).contains("**1 of 2** template cases meet the bar")
			.contains("2 of 2 inside Python's seed range, 1 of 2 within 2% of its median; 0 with a seed")
			.contains("**32 of 40** runs replay the frozen engine's exactly");
	}

	@Test
	void render_everyCaseRunAndKneeMatchingExactly_isTheHeadlineAboveTheFormalBar() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()),
				List.of(ParityRuns.kneeRow(1000.0, 1000.0)), NO_TEMPLATE_TIMED);

		assertThat(headline(page)).contains("**Every case, run and kept knee matches the frozen engine exactly.** All "
				+ "20 runs of the 1 template cases replay it figure for figure and digest for digest, and all 1 kept "
				+ "calibrations' knees are its knees.");
	}

	@Test
	void render_kneesThatDifferWithinTheBar_keepTheHeadlineFromClaimingAnExactMatch() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()),
				List.of(ParityRuns.kneeRow(1000.0, 1015.0)), NO_TEMPLATE_TIMED);

		assertThat(headline(page)).contains("**Stage 4's bar is met.** 1 of 1 template cases and 1 of 1 kept knees "
				+ "meet it; 20 of 20 runs replay the frozen engine exactly.");
	}

	@Test
	void render_aBarNotMet_isTheHeadlineWithItsCounts() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()),
				List.of(ParityRuns.kneeRow(1000.0, 1000.0)), NO_TEMPLATE_TIMED);

		assertThat(headline(page)).contains("**Stage 4's bar is not met.** 1 of 2 template cases and 1 of 1 kept "
				+ "knees meet it; 32 of 40 runs replay the frozen engine exactly.");
	}

	@Test
	void render_aKneeBeyondTheBar_isABarNotMetEvenWhenEveryRunReplays() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()),
				List.of(ParityRuns.kneeRow(1000.0, 1100.0)), NO_TEMPLATE_TIMED);

		assertThat(headline(page))
			.contains("**Stage 4's bar is not met.** 1 of 1 template cases and 0 of 1 kept knees");
	}

	@Test
	void render_noKneeChecked_isNoBarMet() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(headline(page)).contains("**Stage 4's bar is not met.**");
	}

	@Test
	void render_countsOfRuns_areWrittenWithThousandsSeparators() {
		ObjectNode whole = ParityRuns.parity(ParityRuns.met())
			.put("runs", 2980)
			.put("exactReplays", 2980)
			.put("digestsEqual", 2980);

		String page = JavaParityPage.render(whole, List.of(ParityRuns.kneeRow(1000.0, 1000.0)), NO_TEMPLATE_TIMED);

		assertThat(page).contains("All 2,980 runs")
			.contains("**2,980 of 2,980** runs replay the frozen engine's exactly")
			.contains("2,980 give its result digest");
	}

	@Test
	void render_runsDepartingOnlyWhereTheBaselinesKeepTooLittle_countsAndNamesEachDeparture() {
		ObjectNode parity = ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()).put("departingRuns", 8);
		parity.putObject("departures").put("refusedLookups", 8);

		String page = JavaParityPage.render(parity, List.of(), NO_TEMPLATE_TIMED);

		assertThat(page)
			.contains("- Of the 8 runs that do not replay it exactly, 8 depart only where the baselines "
					+ "keep too little to read them as the frozen engine's, every figure they keep its but the result "
					+ "digest: in 8, a cache refused lookups")
			.contains("[ADR-0082](../adr/0082-count-a-caches-refused-lookups-in-its-hit-ratio.md)")
			.contains("; none departs otherwise.");
	}

	@Test
	void render_aDepartureThePageHasNoWordsFor_isRefused() {
		ObjectNode parity = ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()).put("departingRuns", 8);
		parity.putObject("departures").put("unknownDeparture", 8);

		assertThatIllegalStateException().isThrownBy(() -> JavaParityPage.render(parity, List.of(), NO_TEMPLATE_TIMED))
			.withMessageContaining("unknownDeparture");
	}

	@Test
	void render_runsDepartingOtherwise_saysHowMany() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(page).contains("- Of the 8 runs that do not replay it exactly, none departs only where the "
				+ "baselines keep too little to read them as the frozen engine's; 8 depart otherwise.");
	}

	@Test
	void render_everyRunReplayingExactly_saysNothingOfDepartures() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).doesNotContain("do not replay it exactly");
	}

	@Test
	void render_anyParityRun_namesWhatItReadsAsTheFrozenEngines() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(section(page, "How it was measured")).contains("- The departures: ")
			.contains("[ADR-0081](../adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)")
			.contains("[ADR-0083](../adr/0083-name-each-utilisation-and-compare-like-with-like.md)")
			.contains("[ADR-0084](../adr/0084-judge-asynchronous-designs-by-their-messages-too.md)")
			.contains("[ADR-0085](../adr/0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md)");
	}

	@Test
	void render_theParityRun_givesNoSpeedFigure() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(page).contains("- The Java runs: `./gradlew :archsim-engine:parity "
				+ "-Parchsim.parity.out=docs/validation` simulated the same runs (4 at a time) and wrote "
				+ "`simulation-parity.json`; the `javaSeconds` it records is not a speed figure.");
	}

	@Test
	void render_aCaseThatMisses_isShownWithEachNumberItMissedBy() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(section(page, "Cases that miss the bar"))
			.contains("| queueing-lab | spike | p95 | 18 to 26 | 20 | 21 | yes | no (5.00%) |");
	}

	@Test
	void render_noCaseMissing_saysSo() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(section(page, "Cases that miss the bar")).contains("None:");
	}

	@Test
	void render_noMetricOff_saysSoInsteadOfNamingOne() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).contains("- No metric is off: every Java median is Python's.")
			.doesNotContain("The metric furthest");
	}

	@Test
	void render_aCaseWithNoMetricOff_showsNoneOff() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).contains("| queueing-lab | base | 20 | 20 | yes | none off | | | |");
	}

	@Test
	void render_theWorstMetric_isNamedWithItsNumbers() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.missed()), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(page)
			.contains("queueing-lab `spike`, p95: Java's median 21 against Python's 20 (18 to 26), 5.00% off");
	}

	@Test
	void render_aMetricNoJavaRunMeasured_isShownAsUnmeasured() {
		ObjectNode unmeasured = ParityRuns.scenario("cold", 0,
				ParityRuns.verdict("utilization api", new ParityRuns.Seeds(0.4, 0.6, 0.5), null));

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), unmeasured), List.of(),
				NO_TEMPLATE_TIMED);

		assertThat(page)
			.contains("| queueing-lab | cold | utilization api | 0.4 to 0.6 | 0.5 | not measured | no | no |");
	}

	@Test
	void render_aCaseWhoseRunsFailedOtherwiseThanPythons_isListedInItsOwnWords() {
		ObjectNode failing = ParityRuns.scenario("cold", 0,
				ParityRuns.verdict("p50", new ParityRuns.Seeds(9.0, 11.0, 10.0), 10.0));
		failing.putObject("failures").put("python", 0).put("java", 3).put("mismatched", 3);
		failing.put("meetsTheBar", false);
		failing.putArray("missed").add("3 seeds failed otherwise than Python's (Java failed 3, Python 0)");

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), failing), List.of(), NO_TEMPLATE_TIMED);

		assertThat(section(page, "Cases that miss the bar"))
			.contains("- queueing-lab `cold`: 3 seeds failed otherwise than Python's (Java failed 3, Python 0)")
			.doesNotContain("| queueing-lab | cold |");
	}

	@Test
	void render_aReportWithNoMetricMeasured_saysSo() {
		ObjectNode unmeasured = ParityRuns.parity(ParityRuns.met());
		unmeasured.remove("worst");

		String page = JavaParityPage.render(unmeasured, List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).contains("- No metric was measured in any case.");
	}

	@Test
	void render_speedMeasured_saysHowManyRunsAndThatStrictMathIsIncluded() {
		Speed speed = Speed.of(SpeedRows.of(ParityRuns.timed(1.5, 1.5, 1.5), ParityRuns.timed(0.15, 0.15, 0.15)));

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), speed);

		assertThat(page).contains("the median of 3 runs on each engine")
			.contains("include StrictMath's cost")
			.contains("| queueing-lab | 24,154 | 1.50 s | 0.15 s | x10.0 | yes |")
			.contains("- The speed: `" + JavaParityPage.PYTHON_SPEED_COMMAND + "`, then `"
					+ JavaParityPage.JAVA_SPEED_COMMAND + "`, one after the other with no other heavy work running.");
	}

	@Test
	void render_speedMeasured_pointsToPerformanceForHowMuchTheRunsSpread() {
		Speed speed = Speed.of(SpeedRows.of(ParityRuns.timed(1.5, 1.5, 1.5), ParityRuns.timed(0.15, 0.15, 0.15)));

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), speed);

		assertThat(page).contains("[docs/performance.md](../performance.md) says how much the runs spread");
	}

	@Test
	void render_speedMeasuredWithNoTemplateTimed_saysSo() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), NO_TEMPLATE_TIMED);

		assertThat(section(page, "Speed")).contains("No template was timed.");
		assertThat(page).doesNotContain("- The speed:");
	}

	@Test
	void render_aTemplateJavaDidNotTime_isShownUnmeasured() {
		Speed speed = Speed.of(SpeedRows.of(ParityRuns.timed(1.5), JsonText.read("{\"templates\": []}")));

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), speed);

		assertThat(page).contains("| queueing-lab | 24,154 | 1.50 s | not measured | not measured | no |");
	}

	@Test
	void render_speedNotYetMeasured_saysSoWithTheCommandsThatMeasureIt() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), Speed.notMeasured());

		assertThat(section(page, "Speed")).contains("Not yet measured")
			.contains("a quiet machine")
			.contains("`" + JavaParityPage.PYTHON_SPEED_COMMAND + "`, then `" + JavaParityPage.JAVA_SPEED_COMMAND
					+ "`, with no other heavy work")
			.contains("not part of the bar");
		assertThat(page).doesNotContain("- The speed:");
	}

	@Test
	void render_aJavaCheckWithoutAFrozenEngineOne_isShownWithoutItsPythonKnee() {
		ObjectNode javaOnly = ParityRuns.kneeRow(1000.0, 800.0)
			.put("calibration", "2026-11-03T10-00-00Z")
			.putNull("python")
			.put("checked", false)
			.putNull("sameInputs")
			.put("withinTwoPercent", false);

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()),
				List.of(ParityRuns.kneeRow(1000.0, 1000.0), javaOnly), NO_TEMPLATE_TIMED);

		assertThat(page).contains("**1 of 2** kept calibrations")
			.contains("| pg-mixed | 2026-11-03T10-00-00Z | no frozen-engine check | 800 |  | no |");
	}

	@Test
	void render_aPairWithOtherDerivedInputs_countsItAndMarksItsRow() {
		ObjectNode row = ParityRuns.kneeRow(1000.0, 1000.0).put("sameInputs", false);

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(row), NO_TEMPLATE_TIMED);

		assertThat(page).contains("1 with other derived inputs than Python's")
			.contains("| pg-mixed | 2026-10-26T08-23-18Z | 1,000 | 1,000 | no | yes |");
	}

	@Test
	void render_enginesTimingDifferentRunCounts_givesTheRange() {
		Speed speed = Speed
			.of(SpeedRows.of(ParityRuns.timed(1.5, 1.5, 1.5), ParityRuns.timed(1.0, 1.0, 1.0, 1.0, 1.0)));

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(), speed);

		assertThat(page).contains("the median of each template's runs (3 to 5)");
	}

	@Test
	void render_aCalibrationJavaDidNotResimulate_saysSo() {
		ObjectNode row = ParityRuns.kneeRow(1000.0, 1000.0)
			.putNull("java")
			.put("resimulated", false)
			.putNull("sameInputs")
			.put("withinTwoPercent", false);

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(row), NO_TEMPLATE_TIMED);

		assertThat(page).contains("| pg-mixed | 2026-10-26T08-23-18Z | 1,000 | not re-simulated |  | no |");
	}

	@Test
	void render_checksThatFoundNoKnee_sayNone() {
		ObjectNode row = ParityRuns.kneeRow(1000.0, 1000.0).putNull("python").putNull("java");

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met()), List.of(row), NO_TEMPLATE_TIMED);

		assertThat(page).contains("| pg-mixed | 2026-10-26T08-23-18Z | none | none | yes | yes |");
	}

	// the missed metrics' table, then the runs that failed otherwise, a blank line apart
	@Test
	void render_aMissedMetricAndARunThatFailedOtherwise_listsBoth() {
		ObjectNode missed = ParityRuns.missed();
		missed.withArray("missed").add("a seed failed where Python's ran");

		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), missed), List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).contains("|\n\nRuns that failed otherwise than Python's:\n\n"
				+ "- queueing-lab `spike`: a seed failed where Python's ran");
	}

	@Test
	void render_aCaseWithoutMetrics_saysNoneMeasured() {
		String page = JavaParityPage.render(ParityRuns.parity(ParityRuns.met(), ParityRuns.scenario("idle", 20)),
				List.of(), NO_TEMPLATE_TIMED);

		assertThat(page).contains("| queueing-lab | idle | 20 | 20 | yes | none measured | | | |");
	}

	private static String headline(String page) {
		return page.split("The formal criterion", 2)[0];
	}

	private static String section(String page, String title) {
		return page.split("## " + title, 2)[1].split("\n## ", 2)[0];
	}

}
