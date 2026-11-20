package io.archsim.benchmarks.inputs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.machine.ContentionFit;
import io.archsim.benchmarks.machine.KnownMachine;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.calibration.resources.RatePoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the service's CPU per request continues above the staircase's top rate: flat, as
 * the derivation keeps it, or carried on in log(rate) when asked. The staircase is a
 * protocol v6 one whose cost keeps falling near its top (cache-aside's service, W26).
 */
class LoadCurveFormTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	// each rate's passes, in milliseconds of CPU per request
	private static final List<RatePoint> V6 = List.of(new RatePoint(50, 1.0), new RatePoint(50, 1.04),
			new RatePoint(650, 0.30), new RatePoint(650, 0.29), new RatePoint(2000, 0.211), new RatePoint(2000, 0.206),
			new RatePoint(3000, 0.186), new RatePoint(3000, 0.181), new RatePoint(3000, 0.19));

	// each rate's median
	private static final JsonNode V6_BY_LOAD = byLoad(Map.of(50.0, 1.02, 650.0, 0.295, 2000.0, 0.2085, 3000.0, 0.186));

	@Test
	void curve_theFlatForm_leavesThePassesOut() {
		assertThat(LoadCurveInput.curve(V6_BY_LOAD, V6, 1, CurveForm.FLAT))
			.isEqualTo(LoadCurveInput.curve(V6_BY_LOAD, List.of(), 1, CurveForm.FLAT));
	}

	@Test
	void curve_theFlatForm_endsAtTheTopRate() {
		JsonNode points = LoadCurveInput.curve(V6_BY_LOAD, V6, 1, CurveForm.FLAT).orElseThrow().get("value");

		assertThat(points.get(points.size() - 1).get("rps").doubleValue()).isEqualTo(3000.0);
		assertThat(points.get(points.size() - 1).get("factor").doubleValue()).isEqualTo(1.0);
	}

	// as far above the top as the base spans below it:
	// 2,000 and 3,000 req/s, the rates at least 30% busy
	@Test
	void curve_theLogForm_addsAPointOneMeasuredStretchAbove() {
		JsonNode points = LoadCurveInput.curve(V6_BY_LOAD, V6, 1, CurveForm.LOG).orElseThrow().get("value");

		assertThat(points.valueStream().map((point) -> point.get("rps").doubleValue())).containsExactly(50.0, 650.0,
				2000.0, 3000.0, 4500.0);
		assertThat(points.get(3).get("factor").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void curve_theLogForm_carriesTheFallOn() {
		JsonNode points = LoadCurveInput.curve(V6_BY_LOAD, V6, 1, CurveForm.LOG).orElseThrow().get("value");

		assertThat(points.get(4).get("factor").doubleValue()).isGreaterThan(0.8).isLessThan(1.0);
	}

	@Test
	void curve_theLogForm_saysHowFarItCarriedTheFall() {
		String how = LoadCurveInput.curve(V6_BY_LOAD, V6, 1, CurveForm.LOG).orElseThrow().get("how").stringValue();

		assertThat(how).contains("carried from 3,000 to 4,500 req/s (one measured stretch on)").endsWith("then flat");
	}

	@Test
	void of_theLogForm_carriesTheStaircasesFallOn() {
		JsonNode points = LoadCurveInput.of(v6Calibration(), 1, CurveForm.LOG, null).orElseThrow().get("value");

		assertThat(points.get(points.size() - 1).get("rps").doubleValue()).isEqualTo(4500.0);
	}

	@Test
	void of_theLawForm_isTheLaw() {
		assertThat(LoadCurveInput.of(v6Calibration(), 1, CurveForm.LAW, null))
			.isEqualTo(LawCurveInput.of(v6Calibration(), 1, null));
	}

	@Test
	void of_theFlatFormOnAMachine_isTheFlatCurve() {
		MachineFit fit = ContentionFit.of(KnownMachine.check()).orElseThrow();

		assertThat(LoadCurveInput.of(v6Calibration(), 1, CurveForm.FLAT, fit))
			.isEqualTo(LoadCurveInput.of(v6Calibration()));
	}

	// the derivation keeps the curve flat even where the log form would carry it on
	@Test
	void of_aStaircaseTheLogFormWouldCarryOn_endsAtItsTop() {
		JsonNode points = LoadCurveInput.of(v6Calibration()).orElseThrow().get("value");

		assertThat(points.get(points.size() - 1).get("rps").doubleValue()).isEqualTo(3000.0);
		assertThat(points.get(points.size() - 1).get("factor").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void of_aStaircaseTheLogFormWouldCarryOn_saysItIsFlatOutsideIt() {
		String how = LoadCurveInput.of(v6Calibration()).orElseThrow().get("how").stringValue();

		assertThat(how).endsWith("; flat outside the staircase");
	}

	// 2 ms a second at rest, over the pass's 30 s
	@Test
	void staircaseCpu_eachPass_isNetOfIdle() {
		JsonNode idle = JsonText.read("{\"serviceCpuMsPerSecond\": 2.0}");

		assertThat(LoadCurveInput.staircaseCpu(staircase(), idle)).containsExactly(
				new RatePoint(50.0, (1560 - 60.0) / 1500), new RatePoint(50.0, (1620 - 60.0) / 1500),
				new RatePoint(900.0, (5460 - 60.0) / 27000));
	}

	// before protocol v5: no idle pass
	@Test
	void staircaseCpu_noIdlePass_isNone() {
		assertThat(LoadCurveInput.staircaseCpu(staircase(), JsonNodeFactory.instance.nullNode())).isEmpty();
	}

	// every kept v5 staircase topped out at a third of the core:
	// never carried on above its top, even in the log form
	@ParameterizedTest
	@MethodSource("keptV5Calibrations")
	void curve_aKeptV5Calibration_staysAsItWas(Path calibrationFile, int serviceCpus) {
		JsonNode calibration = ResultFiles.read(calibrationFile).orElseThrow();
		List<RatePoint> passes = LoadCurveInput.staircaseCpu(calibration.get("staircase"), calibration.get("idle"));

		assertThat(LoadCurveInput.curve(calibration.get("cpuByLoad"), passes, serviceCpus, CurveForm.LOG))
			.isEqualTo(LoadCurveInput.of(calibration));
	}

	// each experiment's kept calibrations under protocol v5, with its service's CPUs
	static Stream<Arguments> keptV5Calibrations() {
		Cpusets cpusets = Cpusets.declaredIn(LAYOUT.compose());
		return Experiments.standard().all().stream().flatMap((experiment) -> {
			String service = Stacks.standard().containers(experiment.stack()).getFirst();
			int cpus = Cpusets.count(Objects.requireNonNull(cpusets.of(experiment.stack(), Map.of()).get(service)));
			return calibrations(LAYOUT.results().resolve(experiment.id())).map((file) -> Arguments.of(file, cpus));
		});
	}

	// a held-out experiment not yet measured has no folder, and so no calibration
	private static Stream<Path> calibrations(Path folder) {
		if (!Files.isDirectory(folder)) {
			return Stream.empty();
		}
		try (Stream<Path> runs = Files.list(folder)) {
			return Stream
				.concat(Stream.of(folder), runs.filter((run) -> run.getFileName().toString().startsWith("run-")))
				.map((run) -> run.resolve("calibration.json"))
				.filter((file) -> "v5".equals(ResultFiles.read(file).orElseThrow().path("protocol").asString()))
				.toList()
				.stream();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// the V6 staircase as a calibration records it:
	// 1,000 requests a pass, nothing at rest
	private static ObjectNode v6Calibration() {
		ObjectNode passes = JsonNodeFactory.instance.objectNode().set("cpuByLoad", V6_BY_LOAD.deepCopy());
		ArrayNode staircase = passes.putArray("staircase");
		V6.forEach((pass) -> staircase.addObject()
			.put("rateRps", pass.rate())
			.putObject("service")
			.put("cpuMs", pass.value() * 1000)
			.put("requests", 1000));
		passes.putObject("idle").put("serviceCpuMsPerSecond", 0.0);
		return passes;
	}

	// three passes of 30 s: the service's CPU and its requests
	private static ArrayNode staircase() {
		return (ArrayNode) JsonText.read("""
				[{"rateRps": 50, "service": {"cpuMs": 1560.0, "requests": 1500}},
				 {"rateRps": 50, "service": {"cpuMs": 1620.0, "requests": 1500}},
				 {"rateRps": 900, "service": {"cpuMs": 5460.0, "requests": 27000}}]""");
	}

	// each rate's CPU per request, as cpuByLoad records them
	private static ArrayNode byLoad(Map<Double, Double> cpuMsByRate) {
		ArrayNode rates = JsonNodeFactory.instance.arrayNode();
		cpuMsByRate.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach((rate) -> rates.addObject()
				.put("rateRps", rate.getKey())
				.put("passes", 2)
				.put("serviceCpuMsPerRequest", rate.getValue()));
		return rates;
	}

}
