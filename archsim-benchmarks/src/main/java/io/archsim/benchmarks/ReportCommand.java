package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.report.Reports;

/**
 * {@code report}: rewrites {@code docs/validation.md}'s generated blocks and its charts
 * from {@code benchmarks/results}; with {@code --json}, {@code accuracy.json} and its
 * copy in the web app; with {@code --dev}, the development checks' page and the knee
 * range's.
 */
@Command(name = "report",
		description = "rewrite the generated parts of docs/validation.md and its charts from benchmarks/results",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class ReportCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--json",
			description = "also write docs/validation/accuracy.json and its copy apps/web/src/data/accuracy.json")
	private boolean json;

	@Option(names = "--dev", description = "also write docs/validation/dev-checks.md (development checks, in-sample) "
			+ "and docs/validation/knee-range.md (the predicted range's fit and check)")
	private boolean dev;

	private final RepositoryLayout layout;

	ReportCommand(RepositoryLayout layout) {
		this.layout = layout;
	}

	@Override
	public Integer call() {
		Reports reports = new Reports(this.layout.results(), this.layout.engines(), this.layout.compose(),
				this.layout.kneeRange());
		if (this.dev) {
			write(this.layout.validation().resolve("dev-checks.md"), reports.devChecks());
			write(this.layout.validation().resolve("knee-range.md"), reports.kneeRange());
		}
		Reports.Page page = reports.validationPage(read(this.layout.validationPage()));
		writeQuietly(this.layout.validationPage(), page.text());
		page.charts().forEach((name, svg) -> writeQuietly(this.layout.validation().resolve(name), svg));
		out().println(
				"wrote " + this.layout.name(this.layout.validationPage()) + " and " + page.charts().size() + " charts");
		if (this.json) {
			String accuracy = reports.accuracy();
			write(this.layout.validation().resolve("accuracy.json"), accuracy);
			write(this.layout.webAccuracy(), accuracy);
		}
		return 0;
	}

	private void write(Path path, String text) {
		writeQuietly(path, text);
		out().println("wrote " + this.layout.name(path));
	}

	private static void writeQuietly(Path path, String text) {
		try {
			Files.createDirectories(Objects.requireNonNull(path.toAbsolutePath().getParent()));
			Files.writeString(path, text, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private PrintWriter out() {
		return this.spec.commandLine().getOut();
	}

}
