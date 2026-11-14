package io.archsim.tools;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.tools.prices.PricesCommand;

/**
 * The repository's tooling that Gradle runs, from the repository's root (ADR-0072): the
 * price refresh. The commit messages' check and CI's changed parts run from their source
 * instead, with no build.
 *
 * <pre>
 * ./gradlew :archsim-tools:run --args="prices aws --region us-east-1"
 * </pre>
 */
@Command(name = "archsim-tools", mixinStandardHelpOptions = true,
		description = "The repository's tooling: the price refresh")
public final class Tools implements Runnable {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	private Tools() {
	}

	/**
	 * Runs the command its arguments name and exits with its exit code, writing UTF-8
	 * whatever the console's encoding.
	 * @param args the command and its arguments
	 */
	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
		PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
		CommandLine commandLine = new CommandLine(new Tools()).addSubcommand(PricesCommand.commandLine())
			.setExecutionExceptionHandler(PricesCommand::failed)
			.setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF))
			.setOut(out)
			.setErr(err);
		System.exit(commandLine.execute(args));
	}

	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
