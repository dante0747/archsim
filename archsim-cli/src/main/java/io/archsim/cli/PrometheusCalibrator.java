package io.archsim.cli;

import java.io.PrintWriter;
import java.time.Clock;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.PrometheusCalibration;
import io.archsim.engine.calibration.PrometheusRequest;
import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.calibration.prometheus.RecordingPrometheus;
import io.archsim.engine.calibration.prometheus.http.HttpPrometheus;
import io.archsim.engine.files.PythonFileException;
import io.archsim.engine.files.PythonFiles;
import io.archsim.model.component.standard.StandardComponents;

/**
 * A calibration from Prometheus as {@code archsim calibrate prometheus} runs it, in the
 * Python CLI's order: the mapping read, the responses' source opened, the range read,
 * then the design, so that of two problems the same one is reported; and the responses
 * recorded once the calibration is done, when they are to be.
 */
final class PrometheusCalibrator {

	private final PrometheusOptions options;

	private final Clock clock;

	/**
	 * A calibration as asked.
	 * @param options what was asked
	 * @param clock what tells the calibration the time it fits its inputs at
	 */
	PrometheusCalibrator(PrometheusOptions options, Clock clock) {
		this.options = options;
		this.clock = clock;
	}

	/**
	 * Calibrates the design, and records the responses when asked to.
	 * @param stderr where the recording is reported
	 * @return the calibration
	 * @throws PythonFileException when a file cannot be read, is not UTF-8, is not JSON
	 * or nests deeper than it is read
	 * @throws CommandException when the recording cannot be written
	 */
	CalibrationOutcome calibrate(PrintWriter stderr) {
		CalibrationMapping mapping = MappingLoader.load(PythonFiles.readText(this.options.mapping()));
		return switch (this.options.source()) {
			case PrometheusSource.Server(String url) -> {
				try (HttpPrometheus server = PrometheusClients.server(url)) {
					yield calibrateRecordingWhenAsked(mapping, server, stderr);
				}
			}
			case PrometheusSource.Recorded(String file) ->
				calibrateRecordingWhenAsked(mapping, PrometheusClients.recorded(file), stderr);
		};
	}

	// the calibration, through a recorder of the client's responses when they are to be
	// recorded
	private CalibrationOutcome calibrateRecordingWhenAsked(CalibrationMapping mapping, PrometheusClient client,
			PrintWriter stderr) {
		String recordTo = this.options.recordTo();
		if (recordTo == null) {
			return calibrated(mapping, client);
		}
		RecordingPrometheus recorder = RecordingPrometheus.of(client);
		CalibrationOutcome outcome = calibrated(mapping, recorder);
		PrometheusClients.save(recorder, recordTo);
		stderr.println("recorded the responses to " + recordTo);
		return outcome;
	}

	private CalibrationOutcome calibrated(CalibrationMapping mapping, PrometheusClient client) {
		String range = this.options.range();
		TimeRange asked = (range != null) ? TimeRange.parse(range, this.options.step()) : null;
		PrometheusRequest request = new PrometheusRequest(PythonFiles.readJson(this.options.design()), mapping, client,
				asked, this.options.replay(), null);
		return new PrometheusCalibration(StandardComponents.registry(), CalibrateCommand.replay(), this.clock)
			.calibrate(request);
	}

}
