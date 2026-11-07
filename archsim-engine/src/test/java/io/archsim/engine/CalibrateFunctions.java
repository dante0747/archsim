package io.archsim.engine;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.OtlpCalibration;
import io.archsim.engine.calibration.OtlpRequest;
import io.archsim.engine.calibration.PrometheusCalibration;
import io.archsim.engine.calibration.PrometheusRequest;
import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.otlp.OtlpMappings;
import io.archsim.engine.calibration.prometheus.RecordedPrometheus;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.text.PythonText;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.JsonDiff;

/**
 * The calibrations themselves, which the golden files record with their simulations and
 * clock injected: a simulation's report is an input, and the clock reads
 * {@link #FIXED_NOW}.
 */
final class CalibrateFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.of(
			"archsim_engine.calibration.apply.calibrate_prometheus", CalibrateFunctions::calibratePrometheus,
			"archsim_engine.calibration.otlp.calibrate_otlp", CalibrateFunctions::calibrateOtlp);

	/** What calibration's clock reads while the golden files are recorded. */
	private static final Clock FIXED_NOW = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);

	private static final double DEFAULT_STEP = 60;

	private CalibrateFunctions() {
	}

	/**
	 * {@code calibrate_prometheus} over the recorded responses, as the recorder calls it.
	 */
	private static JsonNode calibratePrometheus(GoldenEntry entry) {
		JsonNode source = entry.input("mapping");
		CalibrationMapping mapping = source.isString() ? MappingLoader.load(source.stringValue())
				: MappingLoader.load(source);
		JsonNode step = entry.input("step");
		double rangeStep = PythonText.truthy(step) ? step.doubleValue()
				: (mapping.range() != null) ? mapping.range().step() : DEFAULT_STEP;
		JsonNode range = entry.input("range");
		TimeRange given = PythonText.truthy(range) ? TimeRange.parse(range.stringValue(), rangeStep) : null;
		RecordedSimulations simulations = new RecordedSimulations(entry.input("simulations"));
		CalibrationOutcome outcome = new PrometheusCalibration(StandardComponents.registry(), simulations, FIXED_NOW)
			.calibrate(new PrometheusRequest(entry.input("document"), mapping,
					RecordedPrometheus.of(entry.input("recorded"), ""), given, entry.input("replay").booleanValue(),
					null));
		simulations.checkAllUsed();
		return outcome.toJson();
	}

	/**
	 * {@code calibrate_otlp} over the recorded traces, its mapping checked as the
	 * recorder's.
	 */
	private static JsonNode calibrateOtlp(GoldenEntry entry) {
		RecordedSimulations simulations = new RecordedSimulations(entry.input("simulations"));
		CalibrationOutcome outcome = new OtlpCalibration(simulations, FIXED_NOW).calibrate(new OtlpRequest(
				entry.input("document"), entry.input("traces"), OtlpMappings.load(entry.input("mapping")).orElse(null),
				entry.input("minSamples").intValue(), null));
		simulations.checkAllUsed();
		return outcome.toJson();
	}

	/**
	 * The simulations an entry recorded, returned in order in place of simulating, each
	 * once the design given is the one it simulated: the model its report's {@code input}
	 * holds, as {@code run_simulation} resolves the design.
	 */
	static final class RecordedSimulations implements ReplaySimulator {

		private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
				PriceSnapshots.shipped());

		private final Deque<JsonNode> reports = new ArrayDeque<>();

		RecordedSimulations(JsonNode reports) {
			reports.forEach((report) -> this.reports.add(report.deepCopy()));
		}

		@Override
		public JsonNode simulate(ObjectNode document, @Nullable SimulationLimits limits) {
			JsonNode report = this.reports.poll();
			if (report == null) {
				throw new IllegalStateException("the recorded entry made more simulations than it recorded");
			}
			ObjectNode simulated = DesignDocuments
				.simulationInput(RESOLVER.resolve(DesignDocuments.load(document), null).document());
			List<String> differences = JsonDiff.differences(report.path("input"), simulated);
			if (!differences.isEmpty()) {
				throw new IllegalStateException("the design replayed is not the one Python simulated: " + differences);
			}
			return report;
		}

		void checkAllUsed() {
			if (!this.reports.isEmpty()) {
				throw new IllegalStateException("the recorded entry made fewer simulations than it recorded");
			}
		}

	}

}
