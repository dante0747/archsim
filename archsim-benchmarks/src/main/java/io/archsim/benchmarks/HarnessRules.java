package io.archsim.benchmarks;

import java.util.Map;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

import io.archsim.model.text.PythonText;

/**
 * What every command of the harness keeps to: one that {@code run.py} had does not run
 * while {@code BENCH_ENGINE} names an engine other than Java's, though its help still
 * shows; and a stop ({@link HarnessStopException}) prints its reason alone and exits with
 * 1, as Python's {@code SystemExit} did.
 */
final class HarnessRules {

	/**
	 * Chose the frozen Python engine over Java's until stage 5 of the port (ADR-0062).
	 */
	private static final String ENGINE_VARIABLE = "BENCH_ENGINE";

	private HarnessRules() {
	}

	/**
	 * Makes a command line keep to the rules.
	 * @param commandLine the command line
	 * @param environment the variables the harness started with
	 */
	static void apply(CommandLine commandLine, Map<String, String> environment) {
		commandLine.setExecutionStrategy((parsed) -> {
			Integer help = CommandLine.executeHelpRequest(parsed);
			if (help != null) {
				return help;
			}
			refuseOtherEngines(parsed, environment);
			return new CommandLine.RunLast().execute(parsed);
		});
		commandLine.setExecutionExceptionHandler((ex, command, parsed) -> {
			if (ex instanceof HarnessStopException stop) {
				command.getErr().println(stop.getMessage());
				return 1;
			}
			throw ex;
		});
	}

	// the harness runs the Java engine only (ADR-0062): unset, empty or java is that
	// engine, and another value is refused rather than ignored
	private static void refuseOtherEngines(ParseResult parsed, Map<String, String> environment) {
		String engine = environment.getOrDefault(ENGINE_VARIABLE, "");
		Object command = parsed.asCommandLineList().getLast().getCommand();
		if (command instanceof JavaEngineOnly && !engine.isEmpty() && !"java".equals(engine)) {
			throw new ParameterException(parsed.commandSpec().commandLine(), ENGINE_VARIABLE + "="
					+ PythonText.repr(engine) + ": the harness runs the Java engine only (ADR-0062); unset it");
		}
	}

	/**
	 * A command that refuses to run while {@code BENCH_ENGINE} names an engine other than
	 * Java's: the ones {@code run.py} had, and those added since that run the engine or
	 * name it. {@code report.py}'s and the parity page's never looked at the variable.
	 */
	interface JavaEngineOnly {

	}

}
