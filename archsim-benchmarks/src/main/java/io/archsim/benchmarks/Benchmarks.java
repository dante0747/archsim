package io.archsim.benchmarks;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.engine.version.EngineVersions;

/**
 * The accuracy benchmarks' harness, whose subcommands keep the names {@code run.py} gave
 * them (ADR-0069): {@code compare} recomputes every kept run's comparison.
 */
@Command(name = "archsim-benchmarks",
		description = "ArchSim's accuracy benchmarks: calibrate, predict, measure, "
				+ "compare, and write the validation page from the results",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
public final class Benchmarks implements Runnable {

	/** What {@code --version} prints: the engine the harness simulates with. */
	static final String VERSION = "archsim-benchmarks, engine " + EngineVersions.ENGINE;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	private Benchmarks() {
	}

	/**
	 * Runs the command its arguments name in the repository it is started in, and exits
	 * with its exit code, writing UTF-8 whatever the console's encoding.
	 * @param args the command and its arguments
	 */
	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
		PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
		HarnessLog log = new HarnessLog(out, Clock.system(ZoneId.systemDefault()));
		System.exit(commandLine(RepositoryLayout.current(), log).setOut(out).setErr(err).execute(args));
	}

	/**
	 * The harness's command line, working in a repository and logging to a log.
	 * @param layout the repository
	 * @param log the log
	 * @return the command line, its help and errors in plain text
	 */
	static CommandLine commandLine(RepositoryLayout layout, HarnessLog log) {
		return new CommandLine(new Benchmarks()).addSubcommand(new CompareCommand(layout, log))
			.addSubcommand(new ReportCommand(layout))
			.addSubcommand(new JavaParityCommand(layout))
			.addSubcommand(new TreeIdsCommand(layout))
			.setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF));
	}

	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "a command is required");
	}

}
