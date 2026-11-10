package io.archsim.cli;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.OptionalInt;

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
 * {@code calibrate}, {@code import}, {@code export}, {@code check}, {@code catalog} and
 * {@code schema}.
 */
@Command(name = "archsim", description = "ArchSim architecture simulator",
		subcommands = { ValidateCommand.class, RunCommand.class, CompareCommand.class, AnalyzeCommand.class,
				CalibrateCommand.class, ImportCommand.class, ExportCommand.class, CheckCommand.class,
				CatalogCommand.class, SchemaCommand.class },
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
public final class Archsim implements Runnable {

	/** What {@code --version} prints. */
	static final String VERSION = "archsim, engine " + EngineVersions.ENGINE;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	private final InputStream standardInput;

	private final Clock clock;

	private final Checks checks;

	private Archsim(InputStream standardInput, Clock clock, Checks checks) {
		this.standardInput = standardInput;
		this.clock = clock;
		this.checks = checks;
	}

	/**
	 * Runs the command its arguments name and exits with its exit code, writing UTF-8 to
	 * standard output and error whatever the console's encoding.
	 * @param args the command and its arguments
	 */
	public static void main(String[] args) {
		CommandLine commandLine = commandLine()
			.setOut(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true))
			.setErr(new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
		// an error of the JVM's, which no handler of picocli's sees, ends the
		// command: the command answers it when it answers what it did not expect,
		// else the JVM does
		Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
			OptionalInt answered = answeredError(commandLine, error);
			if (answered.isPresent()) {
				System.exit(answered.getAsInt());
			}
			thread.getThreadGroup().uncaughtException(thread, error);
		});
		System.exit(commandLine.execute(args));
	}

	/**
	 * Answers an error of the JVM's that ended the command, when the command answers what
	 * it did not expect itself, as {@code check} does.
	 * @param commandLine the command line, its arguments parsed
	 * @param error the error
	 * @return the exit code the command answered with; empty when it answers none
	 */
	static OptionalInt answeredError(CommandLine commandLine, Throwable error) {
		ParseResult parsed = commandLine.getParseResult();
		if (parsed == null) {
			return OptionalInt.empty();
		}
		Object command = parsed.asCommandLineList().getLast().getCommand();
		return (command instanceof AnswersUnexpectedErrors answering)
				? OptionalInt.of(answering.answerUnexpected(error)) : OptionalInt.empty();
	}

	/**
	 * The {@code archsim} command line, before its arguments are parsed: its help and its
	 * errors in plain text, and a command refused ({@link CommandException}) by its
	 * message alone, exit code 1. An option is named in full: picocli's abbreviations,
	 * which would take argparse's, fail on {@code -}, the name of standard input.
	 * @return the command line
	 */
	static CommandLine commandLine() {
		return commandLine(System.in, Clock.systemUTC());
	}

	/**
	 * The {@code archsim} command line, reading what a command reads from standard input
	 * ({@code archsim import FORMAT -}) from a stream of its own, and stamping the time a
	 * calibration fits its inputs from a clock of its own.
	 * @param standardInput the stream
	 * @param clock the clock
	 * @return the command line
	 */
	static CommandLine commandLine(InputStream standardInput, Clock clock) {
		return commandLine(standardInput, clock, Checks.standard());
	}

	/**
	 * The {@code archsim} command line, with what runs {@code archsim check}'s check of
	 * its own besides.
	 * @param standardInput the stream
	 * @param clock the clock
	 * @param checks what runs a check
	 * @return the command line
	 */
	static CommandLine commandLine(InputStream standardInput, Clock clock, Checks checks) {
		return new CommandLine(new Archsim(standardInput, clock, checks))
			.setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF))
			.setExecutionExceptionHandler(Archsim::printRefusal);
	}

	// a refusal the user can act on needs its words, not a stack trace; anything
	// else is a bug, which picocli reports with its stack trace, unless the command
	// answers it itself, as check does
	private static int printRefusal(Exception ex, CommandLine commandLine, ParseResult parsed) throws Exception {
		if (ex instanceof CommandException refusal) {
			commandLine.getErr().println(refusal.getMessage());
			return 1;
		}
		if (commandLine.getCommand() instanceof AnswersUnexpectedErrors command) {
			return command.answerUnexpected(ex);
		}
		throw ex;
	}

	/**
	 * What the command reads as standard input.
	 * @return the stream
	 */
	InputStream standardInput() {
		return this.standardInput;
	}

	/**
	 * What tells a calibration the time it fits its inputs at.
	 * @return the clock
	 */
	Clock clock() {
		return this.clock;
	}

	/**
	 * What runs {@code archsim check}'s check.
	 * @return the checks
	 */
	Checks checks() {
		return this.checks;
	}

	/**
	 * Refuses to run without a subcommand, as a usage error.
	 */
	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
