package io.archsim.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import io.archsim.model.text.PythonJson;

/**
 * {@code archsim functions}: the engine's functions the benchmark harness calls
 * ({@link HarnessFunctions}), one JSON request a line on standard input and one answer a
 * line on standard output, until the input ends (ADR-0061). The harness starts one and
 * sends it every call, so a call costs no start of the JVM. Hidden: it is the harness's,
 * not a command for people.
 */
@Command(name = "functions", hidden = true,
		description = "answer the benchmark harness's calls of engine functions, a JSON line each",
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
final class FunctionsCommand implements Callable<Integer> {

	@ParentCommand
	private @Nullable Archsim archsim;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	/**
	 * Answers each request until the input ends.
	 * @return 0
	 * @throws CommandException when standard input cannot be read
	 */
	@Override
	public Integer call() {
		BufferedReader requests = new BufferedReader(
				new InputStreamReader(Objects.requireNonNull(this.archsim).standardInput(), StandardCharsets.UTF_8));
		PrintWriter answers = this.spec.commandLine().getOut();
		try {
			for (String line = requests.readLine(); line != null; line = requests.readLine()) {
				// '\n', not println's separator, ends a line alike on every platform;
				// flushed, for the harness waits for each answer
				answers.print(PythonJson.write(HarnessFunctions.answer(line)) + '\n');
				answers.flush();
			}
		}
		catch (IOException ex) {
			throw new CommandException(
					"Cannot read standard input: " + Objects.requireNonNullElse(ex.getMessage(), "it failed"));
		}
		return 0;
	}

}
