package io.archsim.mcp;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.OtlpCalibration;
import io.archsim.engine.calibration.OtlpRequest;
import io.archsim.engine.calibration.PrometheusCalibration;
import io.archsim.engine.calibration.PrometheusRequest;
import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.calibration.ReplaySummary;
import io.archsim.engine.calibration.mapping.MappingException;
import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.calibration.otlp.OtlpMappings;
import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.calibration.prometheus.RecordedPrometheus;
import io.archsim.engine.files.PythonFiles;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code calibrate}: a design's inputs set from telemetry files, without the network:
 * from Prometheus responses recorded earlier and a mapping, or from an OTLP trace export
 * and an optional mapping; with the replay check, as the Python server answered it.
 */
final class CalibrationTool {

	private final McpSettings settings;

	/**
	 * The tool, its replays held to the server's limits.
	 * @param settings the server's settings
	 */
	CalibrationTool(McpSettings settings) {
		this.settings = settings;
	}

	/**
	 * Calibrates the design.
	 * @param arguments {@code document}, {@code source}, {@code mapping_path},
	 * {@code recorded_path}, {@code traces_path}
	 * @return the calibration, and the replay's summary in one line
	 * @throws ToolRefusal when the files are not given, the document cannot be used, or
	 * the replay is over its limits
	 * @throws io.archsim.engine.files.PythonFileException when a file cannot be read, is
	 * not UTF-8 or is not JSON, in the words of Python's exceptions
	 */
	JsonNode calibrate(Arguments arguments) {
		JsonNode document = arguments.document();
		CalibrationOutcome outcome;
		try {
			outcome = "prometheus".equals(arguments.text("source")) ? fromPrometheus(document, arguments)
					: fromTraces(document, arguments);
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
		catch (SimulationLimitException ex) {
			throw new ToolRefusal("The replay run hit a limit: " + ex.getMessage()
					+ ". Shorten the design's durationS or lower the load.", ex);
		}
		catch (PrometheusException ex) {
			throw new ToolRefusal("Cannot calibrate: " + ex.getMessage(), ex);
		}
		catch (MappingException ex) {
			throw new ToolRefusal(String.valueOf(ex.getMessage()), ex);
		}
		ObjectNode result = outcome.toJson();
		JsonNode calibration = outcome.document().get("calibration");
		result.set("summary",
				ReplaySummary.of(calibration)
					.<JsonNode>map(JsonNodeFactory.instance::stringNode)
					.orElse(JsonNodeFactory.instance.nullNode()));
		return result;
	}

	private CalibrationOutcome fromPrometheus(JsonNode document, Arguments arguments) {
		String mapping = given(arguments.text("mapping_path"));
		String recorded = given(arguments.text("recorded_path"));
		if (mapping == null || recorded == null) {
			throw new ToolRefusal("Prometheus calibration here needs mapping_path and recorded_path (files)");
		}
		// the recording's range, and the replay the Python server always ran
		TimeRange range = null;
		boolean replay = true;
		PrometheusRequest request = new PrometheusRequest(document, MappingLoader.load(PythonFiles.readText(mapping)),
				RecordedPrometheus.of(PythonFiles.readJson(recorded), PythonFiles.fileName(recorded)), range, replay,
				this.settings.limits());
		return new PrometheusCalibration(StandardComponents.registry(), replay(), this.settings.clock())
			.calibrate(request);
	}

	private CalibrationOutcome fromTraces(JsonNode document, Arguments arguments) {
		String traces = given(arguments.text("traces_path"));
		if (traces == null) {
			throw new ToolRefusal("OTLP calibration needs traces_path (an OTLP JSON file)");
		}
		String mappingPath = given(arguments.text("mapping_path"));
		OtlpMapping mapping = (mappingPath != null)
				? OtlpMappings.load(JsonNodeFactory.instance.stringNode(PythonFiles.readText(mappingPath))).orElse(null)
				: null;
		OtlpRequest request = new OtlpRequest(document, PythonFiles.readJson(traces),
				OtlpRequest.sourceNamed(PythonFiles.fileName(traces)), mapping, OtlpRequest.DEFAULT_MIN_SAMPLES,
				this.settings.limits());
		return new OtlpCalibration(replay(), this.settings.clock()).calibrate(request);
	}

	private static ReplaySimulator replay() {
		return ReplaySimulator.of(new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()));
	}

	// a path as Python tested it: by its truth
	private static @Nullable String given(@Nullable String path) {
		return (path == null || path.isEmpty()) ? null : path;
	}

}
