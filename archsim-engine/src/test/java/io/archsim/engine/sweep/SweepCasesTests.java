package io.archsim.engine.sweep;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.check.CheckRequest;
import io.archsim.engine.check.CheckRunner;
import io.archsim.engine.check.CheckSettings;
import io.archsim.engine.check.FailOn;
import io.archsim.engine.check.KneeSweeps;
import io.archsim.engine.check.Measurement;
import io.archsim.engine.check.Thresholds;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load sweeps and checks against the frozen engine's: each case of
 * {@code sweep/cases.json.gz} ({@code scripts/export_sweep_cases.py}), run in Java, gives
 * what Python gave (a breaking point's rows, digests and verdict; breaking points on one
 * grid; a measurement with its knee; whole checks): text and digests exactly, numbers to
 * a relative 1e-9.
 */
class SweepCasesTests {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final BreakingPoint BREAKING_POINT = new BreakingPoint(SIMULATOR, RESOLVER, 4);

	private static final KneeSweeps KNEE_SWEEPS = new KneeSweeps(BREAKING_POINT, RESOLVER);

	private static final CheckRunner CHECKS = new CheckRunner(SIMULATOR, KNEE_SWEEPS);

	@TestFactory
	Stream<DynamicTest> run_eachRecordedCase_givesWhatThePythonEngineGave() {
		return recorded().stream()
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(),
					() -> assertThat(JsonDiff.differences(recorded.get("output"), run(recorded))).isEmpty()));
	}

	@Test
	void sweep_stepsRunAtOnce_isTheSweepRunOneStepAtATime() {
		JsonNode document = recorded().stream()
			.filter((recorded) -> "aKneeInTheRangeFoundFromTheLoad".equals(recorded.get("name").stringValue()))
			.findFirst()
			.orElseThrow()
			.get("document");
		BreakingPoint oneAtATime = new BreakingPoint(SIMULATOR, RESOLVER, 1);

		JsonNode atOnce = BREAKING_POINT.sweep(document, SweepRequest.of(null)).toJson();

		assertThat(atOnce).isEqualTo(oneAtATime.sweep(document, SweepRequest.of(null)).toJson());
	}

	private static JsonNode run(JsonNode recorded) {
		String scenarioId = text(recorded.get("scenarioId"));
		SimulationLimits limits = limits(recorded);
		return switch (recorded.get("fn").stringValue()) {
			case "breaking_point" -> BREAKING_POINT.sweep(recorded.get("document"), sweep(recorded, limits)).toJson();
			case "knee_sweeps" -> knees(recorded, scenarioId, limits);
			case "measure" -> RecordCodec.write(measure(recorded, scenarioId, limits));
			case "run_check" -> RecordCodec.write(CHECKS.check(recorded.get("document"), recorded.get("baseline"),
					new CheckRequest(scenarioId, settings(recorded), recorded.path("knee").asBoolean(), limits)));
			default -> throw new IllegalArgumentException(recorded.get("fn").stringValue());
		};
	}

	private static SweepRequest sweep(JsonNode recorded, SimulationLimits limits) {
		SweepRequest request = SweepRequest.of(text(recorded.get("scenarioId"))).withLimits(limits);
		if (recorded.has("fromRps")) {
			request = request
				.withRange(new SweepRange(recorded.get("fromRps").doubleValue(), recorded.get("toRps").doubleValue()))
				.withSteps(recorded.get("steps").intValue())
				.withTiming(recorded.get("durationS").doubleValue(), recorded.get("warmupS").doubleValue());
		}
		return request;
	}

	private static Measurement measure(JsonNode recorded, @Nullable String scenarioId, SimulationLimits limits) {
		JsonNode document = recorded.get("document");
		return recorded.path("knee").asBoolean() ? CHECKS.measureWithKnee(document, scenarioId, limits)
				: CHECKS.measure(document, scenarioId, limits);
	}

	private static ArrayNode knees(JsonNode recorded, @Nullable String scenarioId, SimulationLimits limits) {
		List<JsonNode> documents = new ArrayList<>();
		recorded.get("documents").forEach(documents::add);
		ArrayNode knees = JsonNodeFactory.instance.arrayNode();
		KNEE_SWEEPS.of(documents, scenarioId, limits).forEach((knee) -> knees.add(RecordCodec.write(knee)));
		return knees;
	}

	private static CheckSettings settings(JsonNode recorded) {
		List<FailOn> failOn = new ArrayList<>();
		recorded.path("failOn").forEach((name) -> failOn.add(FailOn.of(name.stringValue())));
		return new CheckSettings(Thresholds.DEFAULTS, failOn.isEmpty() ? List.of(FailOn.OBJECTIVES) : failOn);
	}

	private static SimulationLimits limits(JsonNode recorded) {
		JsonNode most = recorded.path("limits").path("maxRequests");
		return most.isIntegralNumber()
				? new SimulationLimits(most.longValue(), SimulationLimits.DEFAULT.maxWallSeconds())
				: SimulationLimits.DEFAULT;
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		return (value == null || value.isNull()) ? null : value.stringValue();
	}

	private static List<JsonNode> recorded() {
		try (InputStream compressed = Objects.requireNonNull(SweepCasesTests.class.getResourceAsStream("cases.json.gz"),
				"sweep/cases.json.gz"); InputStream json = new GZIPInputStream(compressed)) {
			List<JsonNode> cases = new ArrayList<>();
			JsonMapper.builder().build().readTree(json).get("cases").forEach(cases::add);
			return cases;
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read sweep/cases.json.gz", ex);
		}
	}

}
