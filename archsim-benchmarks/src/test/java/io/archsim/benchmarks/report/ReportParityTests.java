package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reports regenerated from the committed results are the committed files, byte for
 * byte (ADR-0070): the validation page, its charts, {@code accuracy.json} and its copy in
 * the web app, the development checks' page and the knee range's. The Python harness
 * wrote them; the files name the command that writes them now, the only change the port
 * made to them.
 */
class ReportParityTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Reports REPORTS = new Reports(LAYOUT.results(), LAYOUT.engines(), LAYOUT.compose(),
			LAYOUT.kneeRange());

	@Test
	void validationPage_theCommittedResults_isTheCommittedPage() throws IOException {
		String committed = read(LAYOUT.validationPage());

		Reports.Page page = REPORTS.validationPage(committed);

		assertThat(page.text()).isEqualTo(committed);
	}

	@TestFactory
	Stream<DynamicTest> validationPage_eachChart_isTheCommittedChart() throws IOException {
		Reports.Page page = REPORTS.validationPage(read(LAYOUT.validationPage()));

		return page.charts()
			.entrySet()
			.stream()
			.map((chart) -> DynamicTest.dynamicTest(chart.getKey(),
					() -> assertThat(chart.getValue()).isEqualTo(read(LAYOUT.validation().resolve(chart.getKey())))));
	}

	// the held-out experiments registered and not yet measured have nothing to chart
	@Test
	void validationPage_theCommittedResults_chartsEveryMeasuredExperiment() throws IOException {
		Reports.Page page = REPORTS.validationPage(read(LAYOUT.validationPage()));

		assertThat(page.charts()).containsOnlyKeys("java-mixed.svg", "go-mixed.svg", "python-mixed.svg", "chain.svg",
				"cache-ttl.svg", "pg-mixed.svg", "pg-hot-row.svg", "cache-aside.svg", "kafka.svg");
	}

	@Test
	void accuracy_theCommittedResults_isTheCommittedFile() throws IOException {
		String accuracy = REPORTS.accuracy();

		assertThat(accuracy).isEqualTo(read(LAYOUT.validation().resolve("accuracy.json")));
	}

	@Test
	void accuracy_theWebAppsCopy_isTheDocsFile() throws IOException {
		String web = read(LAYOUT.webAccuracy());

		assertThat(web).isEqualTo(read(LAYOUT.validation().resolve("accuracy.json")));
	}

	@Test
	void devChecks_theCommittedChecks_isTheCommittedPage() throws IOException {
		String page = REPORTS.devChecks();

		assertThat(page).isEqualTo(read(LAYOUT.validation().resolve("dev-checks.md")));
	}

	@Test
	void kneeRange_theCommittedFit_isTheCommittedPage() throws IOException {
		String page = REPORTS.kneeRange();

		assertThat(page).isEqualTo(read(LAYOUT.validation().resolve("knee-range.md")));
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

}
