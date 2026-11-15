package io.archsim.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.TypeConversionException;

import io.archsim.engine.check.CheckSettings;
import io.archsim.engine.check.FailOn;
import io.archsim.engine.check.Thresholds;

/**
 * {@code archsim check}: an SLO gate for CI, as the GitHub Action runs it. A design is
 * simulated and judged against its objectives and, with a baseline from a file or a git
 * ref, against the baseline; the exit code is 0 for a pass, 1 for a failure and 2 for
 * anything else, so 1 only ever means the check failed. What cannot be checked is said in
 * a line, {@code cannot check: ...}, in the Python CLI's words (an invalid design's
 * issues follow it), an error nobody expected included, never with a stack trace.
 */
@Command(name = "check",
		description = "an SLO gate for CI: objectives and regressions; exit 0 pass, 1 fail, 2 invalid input",
		mixinStandardHelpOptions = true, versionProvider = VersionProvider.class)
final class CheckCommand implements Callable<Integer>, AnswersUnexpectedErrors {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@ParentCommand
	private @Nullable Archsim archsim;

	@Parameters(index = "0", paramLabel = "file", description = "the design (JSON)")
	private @Nullable String file;

	@Option(names = "--scenario", description = "check one of its scenarios instead")
	private @Nullable String scenario;

	@ArgGroup(exclusive = true, multiplicity = "0..1")
	private @Nullable Baseline baseline;

	@Option(names = "--fail-on", converter = Choices.FailOns.class,
			description = "what fails the check (repeatable; default: objectives)")
	private @Nullable List<FailOn> failOn;

	@Option(names = "--max-p95-increase", converter = PythonFloat.class, description = "relative (default 0.10)")
	private double maxP95Increase = Thresholds.DEFAULTS.p95Increase();

	@Option(names = "--max-success-drop", converter = PythonFloat.class, description = "absolute (default 0.001)")
	private double maxSuccessDrop = Thresholds.DEFAULTS.successDrop();

	@Option(names = "--max-cost-increase", converter = PythonFloat.class, description = "relative (default 0.10)")
	private double maxCostIncrease = Thresholds.DEFAULTS.costIncrease();

	@Option(names = "--max-knee-drop", converter = PythonFloat.class, description = "relative (default 0.10)")
	private double maxKneeDrop = Thresholds.DEFAULTS.kneeDrop();

	@Option(names = "--knee", description = "also compare breaking points (slower)")
	private boolean knee;

	@Option(names = "--format", converter = Choices.CheckFormats.class,
			completionCandidates = Choices.CheckFormats.class, defaultValue = "markdown",
			description = "one of: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
	private CheckFormat format = CheckFormat.MARKDOWN;

	@Option(names = { "-o", "--out" }, description = "write the result here (default: stdout)")
	private @Nullable String out;

	@Option(names = "--json-out", description = "also write the JSON result here")
	private @Nullable String jsonOut;

	@Option(names = "--report-url", description = "link to the full report in the Markdown")
	private @Nullable String reportUrl;

	/**
	 * Checks the design, prints or writes the result and gives the verdict.
	 * @return 0 for a pass, 1 for a failure, 2 for a check that could not be run
	 */
	@Override
	public Integer call() {
		return new CheckRun(options(), Objects.requireNonNull(this.archsim).checks())
			.run(this.spec.commandLine().getOut(), this.spec.commandLine().getErr());
	}

	/**
	 * Answers an error nobody expected as the Python CLI did, but for its traceback:
	 * {@code cannot check: unexpected error: ...}, exit code 2.
	 * @param unexpected the error
	 * @return 2
	 */
	@Override
	public int answerUnexpected(Throwable unexpected) {
		String message = unexpected.getMessage();
		PrintWriter stderr = this.spec.commandLine().getErr();
		stderr.println(CheckRun.REFUSAL_PREFIX + "unexpected error: "
				+ ((message != null) ? message : unexpected.getClass().getName()));
		// before the JVM exits, for an error of its own
		stderr.flush();
		return CheckRun.CANNOT_CHECK;
	}

	private CheckOptions options() {
		Baseline compared = (this.baseline != null) ? this.baseline : new Baseline();
		List<FailOn> failing = (this.failOn == null || this.failOn.isEmpty()) ? List.of(FailOn.OBJECTIVES)
				: this.failOn;
		CheckSettings settings = new CheckSettings(
				new Thresholds(this.maxP95Increase, this.maxSuccessDrop, this.maxCostIncrease, this.maxKneeDrop),
				failing);
		// a scenario given empty is no scenario, which the result names as given
		return new CheckOptions(Objects.requireNonNull(this.file), this.scenario, PythonOptions.given(compared.file),
				PythonOptions.given(compared.ref), settings, this.knee, this.format, PythonOptions.given(this.out),
				PythonOptions.given(this.jsonOut), PythonOptions.given(this.reportUrl));
	}

	/**
	 * What the design is compared with: a file, or the design's file at a git ref.
	 */
	static final class Baseline {

		@Option(names = "--baseline", required = true, description = "another design file to compare with")
		private @Nullable String file;

		@Option(names = "--baseline-ref", required = true, converter = Refs.class,
				description = "compare with this file at a git ref (e.g. origin/main)")
		private @Nullable String ref;

	}

	/**
	 * A git ref as {@code --baseline-ref} takes one, as argparse's {@code _ref} did:
	 * never one that starts with {@code -}, which git would read as an option.
	 */
	static final class Refs implements ITypeConverter<String> {

		@Override
		public String convert(String ref) {
			if (ref.startsWith("-")) {
				throw new TypeConversionException("a git ref cannot start with '-'");
			}
			return ref;
		}

	}

}
