package io.archsim.cli;

import java.io.PrintWriter;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.check.CheckRequest;
import io.archsim.engine.check.CheckResult;
import io.archsim.engine.check.CheckText;
import io.archsim.engine.files.PythonFileException;
import io.archsim.engine.files.PythonFiles;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * A check as {@code archsim check} runs it, in the Python CLI's order: the design read,
 * then its baseline, from a file or a git ref; the check run; its result written, as
 * Markdown or JSON, to standard output or a file, then its JSON to a file of its own; and
 * the verdict in one line on standard error.
 */
final class CheckRun {

	/** The exit code of a check that could not be run. */
	static final int CANNOT_CHECK = 2;

	/** How a refusal starts. */
	static final String REFUSAL_PREFIX = "cannot check: ";

	private final CheckOptions options;

	private final Checks checks;

	/**
	 * A check as asked.
	 * @param options what was asked
	 * @param checks what runs the check
	 */
	CheckRun(CheckOptions options, Checks checks) {
		this.options = options;
		this.checks = checks;
	}

	/**
	 * Runs the check, or refuses it in one line on standard error, in the Python CLI's
	 * words: an invalid design (its issues below), a run over its limits, a file that
	 * cannot be read or written, a baseline git cannot give.
	 * @param stdout where the result is printed when it is written to no file
	 * @param stderr where the verdict goes, a baseline missing at its ref is said, and a
	 * refusal is
	 * @return the check's exit code: 0 when it passes, 1 when it fails, 2 when it cannot
	 * be run
	 */
	int run(PrintWriter stdout, PrintWriter stderr) {
		try {
			return check(stdout, stderr);
		}
		catch (DocumentInvalidException ex) {
			stderr.println(REFUSAL_PREFIX + "the design is invalid");
			IssueLines.of(ex.issues()).forEach(stderr::println);
		}
		catch (SimulationLimitException | PythonFileException | GitBaselineException ex) {
			stderr.println(REFUSAL_PREFIX + ex.getMessage());
		}
		return CANNOT_CHECK;
	}

	private int check(PrintWriter stdout, PrintWriter stderr) {
		JsonNode document = PythonFiles.readJson(this.options.design());
		JsonNode baseline = baseline(stderr);
		CheckResult result = this.checks.check(document, baseline, new CheckRequest(this.options.scenario(),
				this.options.settings(), this.options.knee(), SimulationLimits.DEFAULT));
		JsonNode json = RecordCodec.write(result);
		String text = (this.options.format() == CheckFormat.MARKDOWN)
				? CheckText.markdown(result, this.options.reportUrl()) : JsonOutput.file(json);
		String out = this.options.out();
		if (out != null) {
			PythonFiles.writeText(out, text);
		}
		else {
			// as _write_stdout wrote it: UTF-8 bytes to sys.stdout.buffer, its line ends
			// as they are on every system
			stdout.print(text);
			stdout.flush();
		}
		String jsonOut = this.options.jsonOut();
		if (jsonOut != null) {
			PythonFiles.writeText(jsonOut, JsonOutput.file(json));
		}
		stderr.println((result.pass() ? "PASS: " : "FAIL: ") + result.headline());
		return result.exitCode();
	}

	private @Nullable JsonNode baseline(PrintWriter stderr) {
		String file = this.options.baseline();
		if (file != null) {
			return PythonFiles.readJson(file);
		}
		String ref = this.options.baselineRef();
		if (ref == null) {
			return null;
		}
		JsonNode atRef = new GitBaseline().at(ref, this.options.design());
		if (atRef == null) {
			stderr.println(this.options.design() + " does not exist at " + ref + ": no baseline");
		}
		return atRef;
	}

}
