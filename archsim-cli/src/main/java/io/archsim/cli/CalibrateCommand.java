package io.archsim.cli;

import java.time.Clock;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim calibrate}: a design's inputs set from telemetry, from Prometheus
 * ({@code prometheus}) or an OpenTelemetry trace export ({@code otlp}).
 */
@Command(name = "calibrate", description = "set a design's inputs from telemetry (docs/calibration.md)",
		subcommands = { CalibratePrometheusCommand.class, CalibrateOtlpCommand.class }, mixinStandardHelpOptions = true,
		versionProvider = VersionProvider.class)
final class CalibrateCommand implements Runnable {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@ParentCommand
	private @Nullable Archsim archsim;

	/**
	 * What tells a calibration the time it fits its inputs at.
	 * @return the clock
	 */
	Clock clock() {
		return Objects.requireNonNull(this.archsim).clock();
	}

	/**
	 * What a calibrated design is replayed on, to check it against what was observed: the
	 * Java simulator, within the engine's limits.
	 * @return the replay
	 */
	static ReplaySimulator replay() {
		return ReplaySimulator.of(new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()));
	}

	/**
	 * Refuses to run without a source, as a usage error.
	 */
	@Override
	public void run() {
		throw new ParameterException(this.spec.commandLine(), "Missing required subcommand");
	}

}
