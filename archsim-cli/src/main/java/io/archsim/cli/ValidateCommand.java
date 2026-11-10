package io.archsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.validation.DocumentValidator;
import io.archsim.model.validation.ValidationResult;

/**
 * {@code archsim validate}: whether a design can be simulated, {@code VALID} or
 * {@code INVALID}, and the issues found (errors block a run, warnings do not), with exit
 * code 1 for an invalid design.
 */
@Command(name = "validate", description = "validate an architecture document", mixinStandardHelpOptions = true,
		version = Archsim.VERSION)
final class ValidateCommand implements Callable<Integer> {

	private static final String INVALID = "INVALID";

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(index = "0", paramLabel = "file", description = "the design to validate")
	private @Nullable Path file;

	/**
	 * Validates the design and prints the verdict and the issues.
	 * @return 0 for a valid design, 1 for an invalid one
	 * @throws CommandException when the design cannot be read
	 */
	@Override
	public Integer call() {
		PrintWriter stdout = this.spec.commandLine().getOut();
		ArchitectureDocument design;
		try {
			design = DesignDocuments.load(CommandFiles.readJson(Objects.requireNonNull(this.file)));
		}
		catch (DocumentInvalidException ex) {
			stdout.println(INVALID);
			IssueLines.of(ex.issues()).forEach(stdout::println);
			return 1;
		}
		ValidationResult result = DocumentValidator.standard().validate(design);
		stdout.println(result.valid() ? "VALID" : INVALID);
		IssueLines.of(result.issues()).forEach(stdout::println);
		return result.valid() ? 0 : 1;
	}

}
