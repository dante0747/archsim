package io.archsim.benchmarks;

import picocli.CommandLine;

/**
 * The machine's checks' commands, {@code envcheck} and {@code contention}, which write
 * under {@code results/envcheck}.
 */
final class MachineCommands {

	private MachineCommands() {
	}

	/**
	 * Adds the machine's checks to a command line.
	 * @param commandLine the command line
	 * @param setup what the checks work with
	 * @return the command line
	 */
	static CommandLine addTo(CommandLine commandLine, HarnessSetup setup) {
		return commandLine.addSubcommand(new EnvcheckCommand(setup)).addSubcommand(new ContentionCommand(setup));
	}

}
