package io.archsim.cli;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.OtlpCalibration;
import io.archsim.engine.calibration.OtlpRequest;
import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.calibration.otlp.OtlpMappings;
import io.archsim.engine.files.PythonFiles;

/**
 * {@code archsim calibrate otlp}: a design calibrated from an OpenTelemetry trace export
 * (OTLP JSON), with an optional mapping of service and database names to parts.
 */
@Command(name = "otlp", description = "from an OpenTelemetry trace export (OTLP JSON)", mixinStandardHelpOptions = true,
		versionProvider = VersionProvider.class)
final class CalibrateOtlpCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@ParentCommand
	private @Nullable CalibrateCommand calibrate;

	@Parameters(index = "0", paramLabel = "file", description = "the design to calibrate")
	private @Nullable String file;

	@Option(names = "--traces", required = true, description = "OTLP JSON file (resourceSpans)")
	private @Nullable String traces;

	@Option(names = "--map", description = "YAML: services: {service.name: part id}, databases: {db: part id}")
	private @Nullable String map;

	@Option(names = { "-o", "--out" }, description = "write the calibrated design here (default: stdout)")
	private @Nullable String out;

	/**
	 * Calibrates the design and prints what was set, then the design.
	 * @return 0 for a calibrated design, 1 for one refused
	 * @throws CommandException when the design cannot be written
	 */
	@Override
	public Integer call() {
		Optional<CalibrationOutcome> outcome = CalibrationAttempt.run(this.spec, this::calibrated, List.of());
		if (outcome.isEmpty()) {
			return 1;
		}
		CalibrationText.write(outcome.get(), PythonOptions.given(this.out), this.spec.commandLine().getOut(),
				this.spec.commandLine().getErr());
		return 0;
	}

	// in the Python CLI's order: the mapping, the design, then the traces
	private CalibrationOutcome calibrated() {
		OtlpMapping mapping = mapping();
		JsonNode design = PythonFiles.readJson(Objects.requireNonNull(this.file));
		String exported = Objects.requireNonNull(this.traces);
		JsonNode spans = PythonFiles.readJson(exported);
		String source = OtlpRequest.sourceNamed(PythonFiles.fileName(exported));
		return new OtlpCalibration(CalibrateCommand.replay(), Objects.requireNonNull(this.calibrate).clock())
			.calibrate(new OtlpRequest(design, spans, source, mapping, OtlpRequest.DEFAULT_MIN_SAMPLES, null));
	}

	// the mapping --map names; none for none, or for one with nothing in it
	private @Nullable OtlpMapping mapping() {
		String named = PythonOptions.given(this.map);
		if (named == null) {
			return null;
		}
		return OtlpMappings.load(JsonNodeFactory.instance.stringNode(PythonFiles.readText(named))).orElse(null);
	}

}
