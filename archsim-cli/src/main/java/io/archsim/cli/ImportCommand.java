package io.archsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.DesignImporter;
import io.archsim.engine.imports.ImportFormat;
import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.engine.imports.ImportReport;
import io.archsim.engine.imports.ImportedDesign;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim import}: a design from a docker-compose file, Kubernetes manifests or a
 * Terraform plan, on standard output or to a file, with what the import mapped, guessed
 * and ignored on standard error; text that is not the format refused with exit code 2.
 */
@Command(name = "import", description = "a design from docker-compose, Kubernetes manifests or a Terraform plan",
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
final class ImportCommand implements Callable<Integer> {

	/** The file name that stands for standard input. */
	private static final String STANDARD_INPUT = "-";

	/** The exit code of text the import refuses. */
	private static final int REFUSED = 2;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@ParentCommand
	private @Nullable Archsim archsim;

	@Parameters(index = "0", paramLabel = "format", converter = Choices.ImportFormats.class,
			completionCandidates = Choices.ImportFormats.class, description = "one of: ${COMPLETION-CANDIDATES}")
	private @Nullable ImportFormat format;

	@Parameters(index = "1", paramLabel = "file",
			description = "the file ('-' for stdin); Terraform: the output of terraform show -json")
	private @Nullable String file;

	@Option(names = "--name", description = "the design's name (default: from the file)")
	private @Nullable String name;

	@Option(names = { "-o", "--out" }, description = "write the design here (default: stdout)")
	private @Nullable String out;

	@Option(names = "--report", description = "write the import report (JSON) here")
	private @Nullable String reportFile;

	/**
	 * Imports the design and prints what the import did.
	 * @return 0 for a design, 2 for text the import refuses
	 * @throws CommandException when the file or standard input cannot be read, or the
	 * design or the report written
	 */
	@Override
	public Integer call() {
		ImportedDesign imported;
		try {
			imported = new DesignImporter(StandardComponents.registry(), PriceSnapshots.shipped())
				.importDesign(Objects.requireNonNull(this.format), TextDecoding.decode(read()), this.name);
		}
		catch (ImportRefusedException | DocumentInvalidException ex) {
			this.spec.commandLine().getErr().println("cannot import: " + ex.getMessage());
			return REFUSED;
		}
		ObjectNode document = imported.document();
		describe(document, imported.report());
		writeReport(imported.report());
		write(document);
		return 0;
	}

	private byte[] read() {
		String named = Objects.requireNonNull(this.file);
		if (!named.equals(STANDARD_INPUT)) {
			return CommandFiles.readBytes(named);
		}
		try {
			return Objects.requireNonNull(this.archsim).standardInput().readAllBytes();
		}
		catch (IOException ex) {
			throw new CommandException(
					"Cannot read standard input: " + Objects.requireNonNullElse(ex.getMessage(), "it failed"));
		}
	}

	// what was mapped, guessed and ignored, the links to confirm, and the counts
	private void describe(ObjectNode document, ImportReport report) {
		PrintWriter stderr = this.spec.commandLine().getErr();
		report.mapped().forEach((line) -> stderr.println("  mapped: " + line));
		report.guessed().forEach((line) -> stderr.println("  guessed: " + line));
		report.ignored().forEach((line) -> stderr.println("  ignored: " + line));
		report.suggestions()
			.forEach((link) -> stderr.println("  connect? %s -> %s (%s)".formatted(link.get("source").stringValue(),
					link.get("target").stringValue(), link.get("reason").stringValue())));
		long errors = report.issues()
			.stream()
			.filter((issue) -> issue.get("severity").stringValue().equals("error"))
			.count();
		JsonNode architecture = document.get("architecture");
		stderr.println("%d parts, %d links; %d guesses to check, %d errors to fix before a run".formatted(
				architecture.get("nodes").size(), architecture.get("edges").size(), report.guessed().size(), errors));
	}

	private void writeReport(ImportReport report) {
		String outputFile = PythonOptions.given(this.reportFile);
		if (outputFile != null) {
			CommandFiles.write(outputFile, JsonOutput.file(report.toJson()));
		}
	}

	private void write(ObjectNode document) {
		String outputFile = PythonOptions.given(this.out);
		if (outputFile == null) {
			JsonOutput.print(this.spec.commandLine().getOut(), document);
			return;
		}
		CommandFiles.write(outputFile, JsonOutput.file(document));
		this.spec.commandLine().getErr().println("wrote " + outputFile);
	}

}
