package io.archsim.benchmarks.inputs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.engine.calibration.resources.CpuFit;
import io.archsim.engine.calibration.resources.CpuFits;
import io.archsim.engine.calibration.resources.MemoryFit;
import io.archsim.engine.calibration.resources.MemoryFits;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.engine.text.PythonJsonReader;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's fitted CPU and memory against what pg-mixed's run 5 (protocol v4, 28 Oct
 * 2026) measured, on the steps below its measured knee, as a calibration over normal
 * operation would see them; nothing starts Docker.
 * <ul>
 * <li>the CPU share predicts the API's CPU at the knee within 15%, where ArchSim
 * predicted a full core before; one cost per request, weighted to the busiest times,
 * under-counts CPU at quiet times by more than 40%, and the curve the fit also gives
 * brings every step within 15%;</li>
 * <li>memory fitted against the request rate predicts every step within 30%, where
 * against requests held it is off by more than 100%; over the whole run, overload
 * included, memory does not grow with requests held at all;</li>
 * <li>PostgreSQL's working set stays flat at every rate.</li>
 * </ul>
 */
class CommittedResourceFitTests {

	private static final Path RUN = RepositoryLayout.current().results().resolve("pg-mixed").resolve("run-5");

	private static final JsonNode MEASURED = read("measured.json");

	private static final JsonNode COMPARISON = read("comparison.json");

	private static final ObjectNode DESIGN = (ObjectNode) read("predictions.json").get("design");

	/** The step the measured knee lies past. */
	private static final double LAST_BELOW_KNEE = COMPARISON.at("/measuredKnee/between/0").doubleValue();

	private static final double WITHIN = 0.15;

	private static final double MS_PER_S = 1000.0;

	@Test
	void measured_theRun_isPgMixedsRunOf28OctoberBelowItsKnee() {
		assertThat(MEASURED.get("experiment").stringValue()).isEqualTo("pg-mixed");
		assertThat(MEASURED.get("at").stringValue()).startsWith("2026-10-28");
		assertThat(steps(true)).extracting((step) -> step.get("offeredRps").intValue())
			.containsExactly(560, 1119, 1678, 2014, 2238);
		assertThat(steps(false))
			.allSatisfy((step) -> assertThat(used(step, "api").get("cores").doubleValue()).isPositive());
	}

	// about half the 0.45 ms service time needs a core, where ArchSim had a whole one
	@Test
	void fitShare_cpuPerRequest_predictsTheCpuAtTheKnee() {
		CpuFit cpu = cpuFit();
		JsonNode top = steps(true).getLast();
		double measured = used(top, "api").get("cores").doubleValue();

		assertThat(cpu.share()).isBetween(0.45, 0.6);
		assertThat(cpu.error()).isNotNull().isLessThanOrEqualTo(WITHIN);
		assertThat(Math.abs(relative(cores(cpu, top), measured))).isLessThan(WITHIN);
		assertThat(relative(before(top).get("cores").doubleValue(), measured)).isGreaterThan(1.0);
	}

	@Test
	void fitShare_cpuPerRequestFallingWithLoad_givesACurveThatFollowsIt() {
		CpuFit cpu = cpuFit();
		List<RatePoint> measured = Objects.requireNonNull(cpu.curve()).measured();
		List<Double> factors = Objects.requireNonNull(cpu.curve()).points().stream().map(RatePoint::value).toList();

		assertThat(measured.getFirst().value()).isGreaterThan(2 * measured.getLast().value());
		assertThat(factors).isSortedAccordingTo((first, second) -> Double.compare(second, first));
		assertThat(factors.getFirst()).isGreaterThan(2);
	}

	// one cost per request, weighted to the busiest times, is low at quiet times, and the
	// curve's factor at each step's rate brings every step within 15%
	@Test
	void fitShare_theCurvesFactorAtEachRate_bringsEveryStepWithinFifteenPercent() {
		CpuFit cpu = cpuFit();
		LoadCurve table = LoadCurve.ofJson(Objects.requireNonNull(cpu.curve()).table());
		JsonNode low = steps(true).getFirst();

		assertThat(relative(cores(cpu, low), used(low, "api").get("cores").doubleValue())).isLessThan(-0.4);
		assertThat(steps(true)).allSatisfy((step) -> {
			double cores = cores(cpu, step) * table.factorAt(step.get("throughputRps").doubleValue());
			assertThat(Math.abs(relative(cores, used(step, "api").get("cores").doubleValue()))).isLessThan(WITHIN);
		});
	}

	// fitted against requests held (the plan's first idea) it is off by more than 100%,
	// and before calibration (256 MiB at idle + 2 MiB per request held) it was 3x high
	@Test
	void fit_memoryAgainstTheRequestRate_followsItWhereRequestsHeldDoNot() {
		List<JsonNode> below = steps(true);
		List<Double> workingSets = workingSets(below);
		MemoryFit byRate = MemoryFits.fit(workingSets, rates(below), null).orElseThrow();
		MemoryFit byHeld = MemoryFits.fit(workingSets, null, held(below)).orElseThrow();

		assertThat(byRate.perRpsMb()).isBetween(0.06, 0.09);
		assertThat(byRate.baselineMb()).isLessThan(10);
		assertThat(byRate.perRequestMb()).isEqualTo(MemoryFits.MIN_PER_REQUEST_MB);
		assertThat(worstError(byRate(byRate, below), workingSets)).isLessThan(0.3);
		assertThat(worstError(byHeld(byHeld, below), workingSets)).isGreaterThan(1.0);
		assertThat(relative(before(below.getLast()).get("memoryMiB").doubleValue(), workingSets.getLast()))
			.isGreaterThan(1.5);
	}

	// overload decouples the requests held from the rate
	@Test
	void fit_theWholeRun_findsMemoryDoesNotGrowWithRequestsHeld() {
		List<JsonNode> every = steps(false);
		double mostHeldBelow = held(steps(true)).stream().mapToDouble(Double::doubleValue).max().orElseThrow();

		MemoryFit both = MemoryFits.fit(workingSets(every), rates(every), held(every)).orElseThrow();

		assertThat(held(every).stream().mapToDouble(Double::doubleValue).max().orElseThrow())
			.isGreaterThan(10 * mostHeldBelow);
		assertThat(both.against()).containsExactly("rate", "held");
		assertThat(both.perRequestMb()).isEqualTo(MemoryFits.MIN_PER_REQUEST_MB);
		assertThat(both.notes()).anyMatch((note) -> note.contains("did not grow with the requests held"));
		assertThat(both.perRpsMb()).isBetween(0.06, 0.09);
	}

	// so databases need no load term
	@Test
	void measured_postgresWorkingSet_staysFlat() {
		List<Double> workingSets = steps(false).stream()
			.map((step) -> used(step, "postgres").get("workingSetMiB").doubleValue())
			.toList();

		assertThat(workingSets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
				/ workingSets.stream().mapToDouble(Double::doubleValue).min().orElseThrow())
			.isLessThan(1.05);
	}

	// the benchmark's design with the fitted inputs, simulated as the harness does,
	// at the knee and at the lowest step
	@Test
	void simulate_theCalibratedDesign_predictsWhatWasMeasured() {
		CpuFit cpu = cpuFit();
		MemoryFit memory = MemoryFits.fit(workingSets(steps(true)), rates(steps(true)), null).orElseThrow();
		JsonNode top = steps(true).getLast();
		JsonNode low = steps(true).getFirst();

		JsonNode atTop = simulate(cpu, memory, top);
		JsonNode atLow = simulate(cpu, memory, low);

		assertThat(Math.abs(relative(atTop.get("cores").doubleValue(), used(top, "api").get("cores").doubleValue())))
			.isLessThan(WITHIN);
		assertThat(Math
			.abs(relative(atTop.get("memoryMiB").doubleValue(), used(top, "api").get("workingSetMiB").doubleValue())))
			.isLessThan(WITHIN);
		assertThat(Math
			.abs(relative(atLow.get("memoryMiB").doubleValue(), used(low, "api").get("workingSetMiB").doubleValue())))
			.isLessThan(WITHIN);
		assertThat(relative(atLow.get("cores").doubleValue(), used(low, "api").get("cores").doubleValue()))
			.isLessThan(-0.4);
	}

	private static List<JsonNode> steps(boolean belowKnee) {
		return MEASURED.get("steps")
			.valueStream()
			.filter((step) -> !belowKnee || step.get("offeredRps").doubleValue() <= LAST_BELOW_KNEE)
			.toList();
	}

	// the CPU cores and working set a container used in the step's load window
	private static JsonNode used(JsonNode step, String container) {
		return step.at("/resources/containers/" + container);
	}

	// what ArchSim predicted for the API at the step before calibration
	private static JsonNode before(JsonNode step) {
		return COMPARISON.get("rows")
			.valueStream()
			.filter((row) -> row.get("offeredRps").equals(step.get("offeredRps")))
			.findFirst()
			.orElseThrow()
			.at("/resources/api/predicted");
	}

	private static CpuFit cpuFit() {
		List<RatePoint> points = steps(true).stream()
			.map((step) -> new RatePoint(step.get("throughputRps").doubleValue(),
					used(step, "api").get("cores").doubleValue()))
			.toList();
		return CpuFits.fitShare(points, apiServiceMs(), 1.0).orElseThrow();
	}

	private static double apiServiceMs() {
		JsonNode api = DESIGN.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("id").stringValue().equals("api"))
			.findFirst()
			.orElseThrow();
		return RecordCodec.read(api.at("/config/serviceTimeMs"), Distribution.class).expected();
	}

	// the cores the fitted share uses at the step's throughput
	private static double cores(CpuFit cpu, JsonNode step) {
		return step.get("throughputRps").doubleValue() * cpu.serviceMs() * cpu.share() / MS_PER_S;
	}

	private static List<Double> workingSets(List<JsonNode> steps) {
		return steps.stream().map((step) -> used(step, "api").get("workingSetMiB").doubleValue()).toList();
	}

	private static List<Double> rates(List<JsonNode> steps) {
		return steps.stream().map((step) -> step.get("throughputRps").doubleValue()).toList();
	}

	// requests the API held on average: throughput times mean latency (Little's law)
	private static List<Double> held(List<JsonNode> steps) {
		return steps.stream()
			.map((step) -> step.get("throughputRps").doubleValue() * step.get("mean").doubleValue() / MS_PER_S)
			.toList();
	}

	// a fit against the rate's working set at each step's throughput, no request held
	private static List<Double> byRate(MemoryFit fit, List<JsonNode> steps) {
		return rates(steps).stream().map((rate) -> fit.baselineMb() + fit.perRpsMb() * rate).toList();
	}

	// a fit against requests held's working set at each step's requests held, no rate
	private static List<Double> byHeld(MemoryFit fit, List<JsonNode> steps) {
		return held(steps).stream().map((held) -> fit.baselineMb() + fit.perRequestMb() * held).toList();
	}

	// the largest relative error of a predicted working set against the measured one
	private static double worstError(List<Double> predicted, List<Double> workingSets) {
		return IntStream.range(0, predicted.size())
			.mapToDouble((index) -> Math.abs(relative(predicted.get(index), workingSets.get(index))))
			.max()
			.orElseThrow();
	}

	// the API's cores and memory with the fitted inputs, at the step's offered load
	private static JsonNode simulate(CpuFit cpu, MemoryFit memory, JsonNode step) {
		ObjectNode design = DESIGN.deepCopy();
		design.withObject("/workload").set("rps", step.get("offeredRps"));
		design.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("id").stringValue().equals("api"))
			.forEach((node) -> ((ObjectNode) node.get("config")).put("cpuShare", cpu.share())
				.put("memoryBaselineMb", memory.baselineMb())
				.put("memoryPerRpsMb", memory.perRpsMb())
				.put("memoryPerRequestMb", memory.perRequestMb()));
		return Simulations.onTheEngine().simulate(design).at("/resources/api");
	}

	private static double relative(double value, double reference) {
		return (value - reference) / reference;
	}

	private static JsonNode read(String name) {
		try {
			return PythonJsonReader.read(Files.readString(RUN.resolve(name), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
