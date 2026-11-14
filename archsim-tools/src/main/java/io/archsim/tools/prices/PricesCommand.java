package io.archsim.tools.prices;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import io.archsim.engine.text.JsonDecodeException;

/**
 * The price refresh's commands, which {@code scripts/prices} were: a provider's snapshot
 * from its public price list, written among the ones archsim-model ships, and
 * {@code diff}, what changed since the last. Run by hand, or by the refresh workflow
 * ({@code .github/workflows/prices.yml}); ArchSim never calls a price API at runtime.
 */
@Command(name = "prices", mixinStandardHelpOptions = true,
		description = "Refresh a price snapshot from a provider's public price list, or summarize what changed")
public final class PricesCommand implements Runnable {

	/** The snapshots archsim-model ships, from the repository's root. */
	static final Path SNAPSHOTS = Path.of("archsim-model", "src", "main", "resources", "io", "archsim", "model",
			"pricing", "snapshots");

	/**
	 * What a price list, a download or a file can fail with: a failure, not a bug.
	 */
	private static final List<Class<? extends Exception>> EXPECTED = List.of(IOException.class,
			UncheckedIOException.class, PriceListException.class, JsonDecodeException.class,
			NumberFormatException.class);

	@Spec
	private CommandSpec spec = CommandSpec.create();

	private PricesCommand() {
	}

	/**
	 * The price commands on this machine: the providers' public endpoints, this process's
	 * environment, the snapshots under the working folder (Gradle's {@code run} task
	 * works in the repository's root) and the system's clock, in its time zone.
	 * @return the command line
	 */
	public static CommandLine commandLine() {
		return commandLine(new PriceSetup(new HttpDownloads(), System.getenv(), SNAPSHOTS.toAbsolutePath(),
				Clock.system(ZoneId.systemDefault())));
	}

	/**
	 * The price commands on a setup.
	 * @param setup the downloads, environment, snapshots and clock
	 * @return the command line, its help and errors in plain text, a failure printed as
	 * {@link #failed} prints it
	 */
	static CommandLine commandLine(PriceSetup setup) {
		return new CommandLine(new PricesCommand()).addSubcommand(new AzureCommand(setup))
			.addSubcommand(new GcpCommand(setup))
			.addSubcommand(new DiffCommand(setup))
			.setExecutionExceptionHandler(PricesCommand::failed)
			.setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF));
	}

	/**
	 * Prints why a command failed and exits with 1, where the reason is the price list's
	 * or the machine's: a download or file that failed, a price list or snapshot that
	 * does not read as expected. Python's traceback ended the script with 1 too. Anything
	 * else is a bug, and is thrown with its stack trace.
	 * @param ex what the command threw
	 * @param commandLine the command
	 * @param parseResult its arguments
	 * @return 1
	 * @throws Exception anything else
	 */
	public static int failed(Exception ex, CommandLine commandLine, ParseResult parseResult) throws Exception {
		if (EXPECTED.stream().noneMatch((type) -> type.isInstance(ex))) {
			throw ex;
		}
		commandLine.getErr().println((ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName());
		return 1;
	}

	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
