package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The validation page rendered while held-out experiments are registered and not yet
 * measured: they are listed, and the page needs no section for one until it has a kept
 * run. {@code ReportParityTests} renders the committed page from the committed results.
 */
class ValidationPageTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final List<String> FIRST_SET = List.of("java-mixed", "go-mixed", "python-mixed", "chain",
			"cache-ttl");

	private static final List<String> SECOND_SET = List.of("python-cores", "python-wide", "java-hot-row", "go-pool",
			"cache-miss");

	// the list is written from the results: the committed page's is empty
	@Test
	void render_noResults_listsEveryHeldOutExperimentAsRegistered(@TempDir Path results) throws IOException {
		Reports.Page page = ValidationPage.render(committedPage(), sources(results));

		assertThat(page.text())
			.contains("\n| java-mixed | " + Experiments.standard().get("java-mixed").title() + " | 2026-10-30 |\n")
			.contains("\n10 held-out experiments are registered with no kept run: ");
	}

	@Test
	void render_theCommittedResults_giveTheSecondSetASectionEach() throws IOException {
		Reports.Page page = ValidationPage.render(committedPage(), sources(LAYOUT.results()));

		assertThat(page.text()).contains(RegisteredBlock.ALL_MEASURED);
		assertThat(SECOND_SET).allSatisfy((experiment) -> assertThat(page.text())
			.contains("## " + experiment + "\n\n<!-- BEGIN GENERATED: " + experiment + " -->\n**"));
	}

	// a section the page has is always rewritten: it never keeps figures the results lack
	@Test
	void render_aSectionOfAnExperimentAwaitingMeasurement_saysItHasNoResults(@TempDir Path results) throws IOException {
		Reports.Page page = ValidationPage.render(committedPage(), sources(results));

		assertThat(FIRST_SET).allSatisfy((experiment) -> assertThat(page.text()).contains("-->\n_No results for "
				+ experiment + " yet: run `./gradlew :archsim-benchmarks:run --args=\"" + experiment + "\"`._\n<!--"));
	}

	// once it has a kept run the page must show it
	@Test
	void render_aMeasuredExperimentWithoutASection_isRefused(@TempDir Path results) throws IOException {
		Runs.keep(results, "cache-miss");
		String page = committedPage()
			.replaceAll("(?s)<!-- BEGIN GENERATED: cache-miss -->.*?<!-- END GENERATED: cache-miss -->\n", "");

		assertThatIllegalStateException().isThrownBy(() -> ValidationPage.render(page, sources(results)))
			.withMessage("docs/validation.md has no generated block 'cache-miss'");
	}

	@Test
	void hasBlock_aPageWithTheBlock_isTrue() {
		String page = "<!-- BEGIN GENERATED: go-pool -->\nbody\n<!-- END GENERATED: go-pool -->\n";

		assertThat(ValidationPage.hasBlock(page, "go-pool")).isTrue();
	}

	// go-mixed's block is not go-pool's
	@Test
	void hasBlock_aPageWithAnotherExperimentsBlock_isFalse() {
		String page = "<!-- BEGIN GENERATED: go-mixed -->\nbody\n<!-- END GENERATED: go-mixed -->\n";

		assertThat(ValidationPage.hasBlock(page, "go-pool")).isFalse();
	}

	private static String committedPage() throws IOException {
		return Files.readString(LAYOUT.validationPage(), StandardCharsets.UTF_8);
	}

	private static ReportSources sources(Path results) {
		return new ReportSources(results, Engines.read(LAYOUT.engines()), Cpusets.declaredIn(LAYOUT.compose()));
	}

}
