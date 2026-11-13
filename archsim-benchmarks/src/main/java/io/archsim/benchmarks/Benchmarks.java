package io.archsim.benchmarks;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.range.RangeFit;
import io.archsim.engine.version.EngineVersions;

/**
 * The accuracy benchmarks' harness, whose subcommands keep the names {@code run.py} gave
 * them (ADR-0069): {@code compare} recomputes every kept run's comparison,
 * {@code resources} predicts the CPU and memory of predictions that have none,
 * {@code resimulate} checks today's model against every kept run, and {@code range-fit}
 * fits the predicted range of the knee.
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
	 * The harness's command line, working in a repository and logging to a log, its range
	 * fit keeping its work in the system's temporary folder ({@link RangeFit#cachePath}).
	 * @param layout the repository
	 * @param log the log
	 * @return the command line, its help and errors in plain text
	 */
	static CommandLine commandLine(RepositoryLayout layout, HarnessLog log) {
		return commandLine(layout, log, RangeFit.cachePath(EngineVersions.ENGINE));
	}

	/**
	 * The harness's command line, working in a repository and logging to a log.
	 * @param layout the repository
	 * @param log the log
	 * @param rangeFitCache the file the range fit keeps its finished work in
	 * @return the command line, its help and errors in plain text
	 */
	static CommandLine commandLine(RepositoryLayout layout, HarnessLog log, Path rangeFitCache) {
		return new CommandLine(new Benchmarks()).addSubcommand(new CompareCommand(layout, log))
			.addSubcommand(new ResourcesCommand(layout, log))
			.addSubcommand(new ResimulateCommand(layout, log))
			.addSubcommand(new RangeFitCommand(layout, log, rangeFitCache))
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
