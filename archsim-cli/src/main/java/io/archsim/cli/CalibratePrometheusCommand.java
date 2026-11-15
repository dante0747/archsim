package io.archsim.cli;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import io.archsim.engine.calibration.CalibrationOutcome;

/**
 * {@code archsim calibrate prometheus}: a design calibrated from Prometheus metrics,
 * through a mapping of which query sets which input, from a server or from responses
 * recorded earlier. The server is called on the open policy, as the Python CLI called it:
 * any host at any address, with the default deadline and size cap (ADR-0057).
 */
@Command(name = "prometheus", description = "from Prometheus metrics, with a mapping file",
		mixinStandardHelpOptions = true, versionProvider = VersionProvider.class)
final class CalibratePrometheusCommand implements Callable<Integer> {

	/** The seconds between points unless {@code --step} says. */
	private static final double DEFAULT_STEP = 60;

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@ParentCommand
	private @Nullable CalibrateCommand calibrate;

	@Parameters(index = "0", paramLabel = "file", description = "the design to calibrate")
	private @Nullable String file;

	@Option(names = "--map", required = true, description = "mapping file (YAML or JSON): which query sets which input")
	private @Nullable String map;

	@ArgGroup(exclusive = true, multiplicity = "1")
	private @Nullable Source source;

	@Option(names = "--range", description = "START/END in ISO 8601 (default: the mapping's range)")
	private @Nullable String range;

	@Option(names = "--step", converter = PythonFloat.class, description = "seconds between points (default 60)")
	private double step = DEFAULT_STEP;

	@Option(names = "--record", description = "also save every response to this file (for offline replays and tests)")
	private @Nullable String recordTo;

	@Option(names = "--no-replay", description = "skip the replay check")
	private boolean noReplay;

	@Option(names = { "-o", "--out" }, description = "write the calibrated design here (default: stdout)")
	private @Nullable String out;

	/**
	 * Calibrates the design and prints what was set, then the design.
	 * @return 0 for a calibrated design, 1 for one refused
	 * @throws CommandException when the recording or the design cannot be written
	 */
	@Override
	public Integer call() {
		Source given = Objects.requireNonNull(this.source);
		PrometheusOptions options = new PrometheusOptions(Objects.requireNonNull(this.file),
				Objects.requireNonNull(this.map), PrometheusSource.of(given.url, given.recorded),
				PythonOptions.given(this.range), this.step, !this.noReplay, PythonOptions.given(this.recordTo));
		PrometheusCalibrator calibrator = new PrometheusCalibrator(options,
				Objects.requireNonNull(this.calibrate).clock());
		Optional<CalibrationOutcome> outcome = CalibrationAttempt.run(this.spec,
				() -> calibrator.calibrate(this.spec.commandLine().getErr()), List.of("  (--no-replay skips it)"));
		if (outcome.isEmpty()) {
			return 1;
		}
		CalibrationText.write(outcome.get(), PythonOptions.given(this.out), this.spec.commandLine().getOut(),
				this.spec.commandLine().getErr());
		return 0;
	}

	/**
	 * Where the responses come from: a server, or responses recorded earlier.
	 */
	static final class Source {

		@Option(names = "--url", required = true, description = "Prometheus base URL, e.g. http://localhost:9090")
		private @Nullable String url;

		@Option(names = "--recorded", required = true,
				description = "replay responses recorded earlier (--record) instead of querying")
		private @Nullable String recorded;

	}

}
