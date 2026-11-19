package io.archsim.benchmarks;

import picocli.CommandLine;

/**
 * The commands that derive data files from the kept runs, without Docker:
 * {@code compare}, {@code resources}, {@code baselines}, {@code resimulate} and
 * {@code range-fit}. The pages written from those files are {@code report}'s and
 * {@code java-parity}'s.
 */
final class ResultsCommands {

	private ResultsCommands() {
	}

	/**
	 * Adds the commands on the kept results to a command line, in the order its help
	 * lists them.
	 * @param commandLine the command line
	 * @param setup what the commands work with
	 * @return the command line
	 */
	static CommandLine addTo(CommandLine commandLine, HarnessSetup setup) {
		RepositoryLayout layout = setup.layout();
		HarnessLog log = setup.log();
		return commandLine.addSubcommand(new CompareCommand(layout, log))
			.addSubcommand(new ResourcesCommand(layout, log))
			.addSubcommand(new BaselinesCommand(layout, log))
			.addSubcommand(new ResimulateCommand(setup))
			.addSubcommand(new RangeFitCommand(setup));
	}

}
