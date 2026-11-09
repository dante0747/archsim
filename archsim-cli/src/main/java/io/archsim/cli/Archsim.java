package io.archsim.cli;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.engine.version.EngineVersions;

/**
 * The {@code archsim} command, whose subcommands do the work: {@code archsim run}.
 */
@Command(name = "archsim", description = "ArchSim architecture simulator", subcommands = RunCommand.class,
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
public final class Archsim implements Runnable {

	/** What {@code --version} prints. */
	static final String VERSION = "archsim, engine " + EngineVersions.ENGINE;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	/**
	 * Runs the command its arguments name and exits with its exit code, writing UTF-8 to
	 * standard output and error whatever the console's encoding.
	 * @param args the command and its arguments
	 */
	public static void main(String[] args) {
		int exitCode = commandLine()
			.setOut(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true))
			.setErr(new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true))
			.execute(args);
		System.exit(exitCode);
	}

	/**
	 * The {@code archsim} command line, before its arguments are parsed: its help and its
	 * errors in plain text, as Python's argparse wrote them.
	 * @return the command line
	 */
	static CommandLine commandLine() {
		return new CommandLine(new Archsim()).setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF));
	}

	/**
	 * Refuses to run without a subcommand, as a usage error.
	 */
	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
