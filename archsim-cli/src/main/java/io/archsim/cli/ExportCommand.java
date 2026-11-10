package io.archsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.export.ExportFormat;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim export}: docker-compose, Kubernetes manifests or a Mermaid diagram from
 * a design, on standard output or to a file; an invalid design's issues instead, with
 * exit code 1.
 */
@Command(name = "export", description = "docker-compose, Kubernetes or Mermaid from a design",
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
final class ExportCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(index = "0", paramLabel = "format", converter = Choices.ExportFormats.class,
			completionCandidates = Choices.ExportFormats.class, description = "one of: ${COMPLETION-CANDIDATES}")
	private @Nullable ExportFormat format;

	@Parameters(index = "1", paramLabel = "file", description = "the design (JSON)")
	private @Nullable Path file;

	@Option(names = { "-o", "--out" }, description = "write here (default: stdout)")
	private @Nullable String out;

	/**
	 * Exports the design.
	 * @return 0 for an export, 1 for an invalid design
	 * @throws CommandException when the design cannot be read or the export written
	 */
	@Override
	public Integer call() {
		PrintWriter stdout = this.spec.commandLine().getOut();
		JsonNode design = CommandFiles.readJson(Objects.requireNonNull(this.file));
		String text;
		try {
			text = new DesignExporter(StandardComponents.registry(), PriceSnapshots.shipped()).export(design,
					Objects.requireNonNull(this.format));
		}
		catch (DocumentInvalidException ex) {
			IssueLines.of(ex.issues()).forEach(stdout::println);
			return 1;
		}
		if (this.out == null) {
			TextOutput.write(stdout, text);
			return 0;
		}
		CommandFiles.write(this.out, text);
		this.spec.commandLine().getErr().println("wrote " + this.out);
		return 0;
	}

}
