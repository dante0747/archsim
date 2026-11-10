package io.archsim.cli;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import io.archsim.engine.version.EngineVersions;

/**
 * The {@code archsim} command, whose subcommands do the work, as the Python engine's
 * CLI's did: {@code validate}, {@code run}, {@code compare}, {@code analyze},
 * {@code import}, {@code export}, {@code catalog} and {@code schema}.
 */
@Command(name = "archsim", description = "ArchSim architecture simulator",
		subcommands = { ValidateCommand.class, RunCommand.class, CompareCommand.class, AnalyzeCommand.class,
				ImportCommand.class, ExportCommand.class, CatalogCommand.class, SchemaCommand.class },
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
public final class Archsim implements Runnable {

	/** What {@code --version} prints. */
	static final String VERSION = "archsim, engine " + EngineVersions.ENGINE;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	private final InputStream standardInput;

	private Archsim(InputStream standardInput) {
		this.standardInput = standardInput;
	}

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
	 * errors in plain text, and a command refused ({@link CommandException}) by its
	 * message alone, exit code 1. An option is named in full: picocli's abbreviations,
	 * which would take argparse's, fail on {@code -}, the name of standard input.
	 * @return the command line
	 */
	static CommandLine commandLine() {
		return commandLine(System.in);
	}

	/**
	 * The {@code archsim} command line, reading what a command reads from standard input
	 * ({@code archsim import FORMAT -}) from a stream of its own.
	 * @param standardInput the stream
	 * @return the command line
	 */
	static CommandLine commandLine(InputStream standardInput) {
		return new CommandLine(new Archsim(standardInput)).setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF))
			.setExecutionExceptionHandler(Archsim::printRefusal);
	}

	// a refusal the user can act on needs its words, not a stack trace; anything
	// else is a bug, which picocli reports with its stack trace
	private static int printRefusal(Exception ex, CommandLine commandLine, ParseResult parsed) throws Exception {
		if (!(ex instanceof CommandException refusal)) {
			throw ex;
		}
		commandLine.getErr().println(refusal.getMessage());
		return 1;
	}

	/**
	 * What the command reads as standard input.
	 * @return the stream
	 */
	InputStream standardInput() {
		return this.standardInput;
	}

	/**
	 * Refuses to run without a subcommand, as a usage error.
	 */
	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
