package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

class PricesCommandTests {

	private final StringWriter out = new StringWriter();

	private final StringWriter err = new StringWriter();

	@Test
	void diff_aNewSnapshotAgainstAnOlder_printsTheSummaryAndTellsGitHubItChanged(@TempDir Path folder)
			throws IOException {
		Path old = copy("diff-old.json", folder.resolve("old.json"));
		Path current = copy("diff-small-new.json", folder.resolve("new.json"));
		Path output = folder.resolve("github_output");
		Files.writeString(output, "earlier=1\n", StandardCharsets.UTF_8);

		int exit = run(setup(folder, Map.of("GITHUB_OUTPUT", output.toString())), "diff", current.toString(),
				"--against", old.toString());

		assertThat(exit).isZero();
		assertThat(this.out).hasToString(Recorded.text("diff-small.md"));
		assertThat(output).hasContent("earlier=1\nchanged=true");
	}

	@Test
	void diff_noSnapshotToCompareWith_comparesWithTheNewestOlderOneBesideIt(@TempDir Path folder) throws IOException {
		copy("diff-old.json", folder.resolve("aws-sample-1-2026-10-01.json"));
		Path current = copy("diff-small-new.json", folder.resolve("aws-sample-1-2026-11-01.json"));

		int exit = run(setup(folder, Map.of()), "diff", current.toString());

		assertThat(exit).isZero();
		assertThat(this.out).hasToString(Recorded.text("diff-small.md"));
	}

	@Test
	void azure_aRegion_writesItsSnapshotAmongTheSnapshots(@TempDir Path folder) {
		FakeDownloads downloads = new FakeDownloads(
				Map.of(AzurePricesTests.EASTUS, AzurePricesTests.page(AzurePricesTests.SAMPLE, null)));

		int exit = run(new PriceSetup(downloads, Map.of(), folder, SnapshotWriterTests.NOVEMBER_14), "azure",
				"--region", "eastus");

		assertThat(exit).isZero();
		assertThat(folder.resolve("azure-eastus-2026-11-14.json"))
			.hasContent(Recorded.text("azure-eastus-2026-11-14.json"));
	}

	@Test
	void gcp_anApiKey_writesTheSnapshotFromTheCatalog(@TempDir Path folder) {
		FakeDownloads downloads = new FakeDownloads(
				Map.of(GcpPricesTests.FIRST_PAGE, GcpPricesTests.page(GcpPricesTests.SAMPLE, "")));
		Path file = folder.resolve("gcp.json");

		int exit = run(
				new PriceSetup(downloads, Map.of("GOOGLE_API_KEY", "k"), folder, SnapshotWriterTests.NOVEMBER_14),
				"gcp", "--region", "us-central1", "--out", file.toString());

		assertThat(exit).isZero();
		assertThat(file).hasContent(Recorded.text("gcp-us-central1-2026-11-14.json"));
	}

	@Test
	void gcp_noApiKey_isRefused(@TempDir Path folder) {
		int exit = run(setup(folder, Map.of()), "gcp", "--region", "us-central1");

		assertThat(exit).isEqualTo(1);
		assertThat(this.err.toString().lines())
			.containsExactly("set GOOGLE_API_KEY (the Cloud Billing Catalog API needs one)");
	}

	@Test
	void azure_aPageThatCannotBeRead_failsSayingWhy(@TempDir Path folder) {
		int exit = run(setup(folder, Map.of()), "azure", "--region", "eastus");

		assertThat(exit).isEqualTo(1);
		assertThat(this.err.toString()).startsWith("no page at https://prices.azure.com/");
		assertThat(folder).isEmptyDirectory();
	}

	@Test
	void prices_noRegion_isRefusedWithTheUsage(@TempDir Path folder) {
		int exit = run(setup(folder, Map.of()), "azure");

		assertThat(exit).isEqualTo(2);
		assertThat(this.err.toString()).contains("Missing required option: '--region=<region>'");
	}

	@Test
	void failed_aBug_isThrownOnWithItsStackTrace(@TempDir Path folder) {
		CommandLine commandLine = PricesCommand.commandLine(setup(folder, Map.of()));
		ParseResult parsed = commandLine.parseArgs("diff", "new.json");
		IllegalArgumentException bug = new IllegalArgumentException("a bug");

		assertThatException().isThrownBy(() -> PricesCommand.failed(bug, commandLine, parsed)).isSameAs(bug);
	}

	@Test
	void failed_aFailureWithoutAMessage_namesItsClass(@TempDir Path folder) throws Exception {
		CommandLine commandLine = PricesCommand.commandLine(setup(folder, Map.of()))
			.setErr(new PrintWriter(this.err, true));
		ParseResult parsed = commandLine.parseArgs("diff", "new.json");

		int exit = PricesCommand.failed(new IOException(), commandLine, parsed);

		assertThat(exit).isEqualTo(1);
		assertThat(this.err.toString().lines()).containsExactly("java.io.IOException");
	}

	private int run(PriceSetup setup, String... arguments) {
		return PricesCommand.commandLine(setup)
			.setOut(new PrintWriter(this.out, true))
			.setErr(new PrintWriter(this.err, true))
			.execute(arguments);
	}

	private static PriceSetup setup(Path folder, Map<String, String> environment) {
		return new PriceSetup(new FakeDownloads(Map.of()), environment, folder, SnapshotWriterTests.NOVEMBER_14);
	}

	private static Path copy(String recorded, Path file) throws IOException {
		Files.writeString(file, Recorded.text(recorded), StandardCharsets.UTF_8);
		return file;
	}

}
