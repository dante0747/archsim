package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.machine.ContentionFit;
import io.archsim.benchmarks.machine.KnownMachine;
import io.archsim.benchmarks.machine.MachineFit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The service's CPU per request as a law of its rate (ADR-0093): fitted on the staircase,
 * carried above its top rate to where the service's CPUs fill, but no further than twice
 * the top rate, and the flat curve where no law falls.
 */
class LawCurveInputTests {

	// 0.07 + 6 × rate^-0.5 ms, which fills one CPU at about 7,076 req/s and two at 17,300
	private static final CostLaw LAW = new CostLaw(0.07, 6.0, 0.5);

	private static final List<Double> TO_4000 = List.of(50.0, 650.0, 2000.0, 4000.0);

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	@Test
	void of_aStaircaseNearTheFill_endsWhereTheCpuFills() {
		JsonNode points = values(LawCurveInput.of(onTheLaw(TO_4000), 1, null).orElseThrow());

		assertThat(last(points).get("rps").doubleValue()).isCloseTo(LAW.fillRps(1).orElseThrow(), within(0.1));
	}

	@Test
	void of_aStaircaseFarBelowTheFill_endsAtTwiceItsTopRate() {
		JsonNode points = values(LawCurveInput.of(onTheLaw(List.of(50.0, 650.0, 2000.0)), 1, null).orElseThrow());

		assertThat(last(points).get("rps").doubleValue()).isEqualTo(4000.0);
	}

	@Test
	void of_theTopRate_isAPointAtAFactorOfOne() {
		JsonNode points = values(LawCurveInput.of(onTheLaw(TO_4000), 1, null).orElseThrow());

		assertThat(points.valueStream().filter((point) -> point.get("rps").doubleValue() == 4000.0)).singleElement()
			.extracting((point) -> point.get("factor").doubleValue())
			.isEqualTo(1.0);
	}

	@Test
	void of_aboveTheTopRate_keepsFalling() {
		JsonNode points = values(LawCurveInput.of(onTheLaw(TO_4000), 1, null).orElseThrow());

		assertThat(last(points).get("factor").doubleValue())
			.isCloseTo(LAW.msAt(LAW.fillRps(1).orElseThrow()) / LAW.msAt(4000), within(1e-4))
			.isLessThan(1.0);
	}

	@Test
	void of_theLowestRate_isTheFirstPoint() {
		JsonNode points = values(LawCurveInput.of(onTheLaw(TO_4000), 1, null).orElseThrow());

		assertThat(points.get(0).get("rps").doubleValue()).isEqualTo(50.0);
		assertThat(points.get(0).get("factor").doubleValue()).isCloseTo(LAW.msAt(50) / LAW.msAt(4000), within(1e-4));
	}

	@Test
	void of_aStaircaseFarBelowTheFill_saysTheLawAndWhereItEnds() {
		String how = how(LawCurveInput.of(onTheLaw(List.of(50.0, 650.0, 2000.0)), 1, null).orElseThrow());

		assertThat(how).contains("fitted as a law of the rate, 0.07 + 6 × rate^-0.5 ms,")
			.endsWith("carried to 4,000 req/s, 2 times the top rate, then flat");
	}

	@Test
	void of_aStaircaseNearTheFill_saysWhereTheCpuFills() {
		String how = how(LawCurveInput.of(onTheLaw(TO_4000), 1, null).orElseThrow());

		assertThat(how).endsWith("carried to 7,076 req/s, where the service's 1 CPU fills, then flat");
	}

	@Test
	void of_twoCpusNearTheirFill_saysWhereTheyFill() {
		String how = how(LawCurveInput.of(onTheLaw(List.of(50.0, 650.0, 2000.0, 10000.0)), 2, null).orElseThrow());

		assertThat(how).endsWith("where the service's 2 CPUs fill, then flat");
	}

	@Test
	void of_aTopRatePastTheFill_endsThereAndSaysSo() {
		String how = how(LawCurveInput.of(onTheLaw(List.of(50.0, 650.0, 2000.0, 8000.0)), 1, null).orElseThrow());

		assertThat(how)
			.endsWith("carried to 8,000 req/s, the top rate, where the service's CPUs had filled, then flat");
	}

	// a law needs three rates; the flat curve takes two
	@Test
	void of_aStaircaseOfTwoRates_isTheFlatCurve() {
		ObjectNode passes = withByLoad(byCost(50.0, 1.0, 2000.0, 0.5));

		assertThat(LawCurveInput.of(passes, 1, null)).isPresent().isEqualTo(LoadCurveInput.of(passes));
	}

	// before protocol v5
	@Test
	void of_aCalibrationWithoutAStaircase_isNone() {
		assertThat(LawCurveInput.of(JsonNodeFactory.instance.objectNode(), 1, null)).isEmpty();
	}

	// a design on a host machine: the law is fitted on the work per request
	@Test
	void of_onAMachine_isTheLawOfTheWorkPerRequest() {
		ObjectNode passes = withStaircase(onTheLaw(TO_4000));

		String how = how(LawCurveInput.of(passes, 1, FIT).orElseThrow());

		assertThat(how)
			.startsWith("the staircase's work per request, its CPU per request net of idle times the "
					+ "machine's thread speed during the pass (")
			.contains(CostLaws.fit(LawCurveInput.workByLoad(passes, FIT)).orElseThrow().describe());
	}

	@Test
	void of_onAMachine_isAnotherLawThanWithout() {
		ObjectNode passes = withStaircase(onTheLaw(TO_4000));

		assertThat(values(LawCurveInput.of(passes, 1, FIT).orElseThrow()))
			.isNotEqualTo(values(LawCurveInput.of(passes, 1, null).orElseThrow()));
	}

	// 0.2 ms per request at 1,600 req/s, measured at the machine's speed then
	@Test
	void workByLoad_aRate_isItsCostTimesTheMachinesSpeedDuringItsPasses() {
		ObjectNode passes = staircase(pass(50, 0.8, 0.8), pass(1600, 0.2, 0.2));

		double speed = MachineInputs.speedDuring(passes.get("staircase").get(1), FIT).orElseThrow();

		assertThat(LawCurveInput.workByLoad(passes, FIT).get(1).value()).isCloseTo(0.2 * speed, within(1e-12));
	}

	// a second pass at 1,600 req/s at twice the cost, on a machine as busy as the first's
	@Test
	void workByLoad_twoPassesAtARate_isTheirMedian() {
		ObjectNode passes = staircase(pass(50, 0.8, 0.8), pass(1600, 0.2, 0.2), pass(1600, 0.4, 0.2));

		double speed = MachineInputs.speedDuring(passes.get("staircase").get(1), FIT).orElseThrow();

		assertThat(LawCurveInput.workByLoad(passes, FIT).get(1).value()).isCloseTo(0.3 * speed, within(1e-12));
	}

	@Test
	void workByLoad_aPassWithoutItsContainersCpu_isLeftOut() {
		ObjectNode passes = staircase(pass(50, 0.8, 0.8), pass(1600, 0.2, 0.2));
		((ObjectNode) passes.get("staircase").get(1)).remove("containerCpuMs");

		assertThat(LawCurveInput.workByLoad(passes, FIT)).hasSize(1);
	}

	@Test
	void workByLoad_noIdlePass_isNone() {
		ObjectNode passes = staircase(pass(50, 0.8, 0.8), pass(1600, 0.2, 0.2));
		passes.remove("idle");

		assertThat(LawCurveInput.workByLoad(passes, FIT)).isEmpty();
	}

	private static JsonNode values(ObjectNode curve) {
		return curve.get("value");
	}

	private static String how(ObjectNode curve) {
		return curve.get("how").stringValue();
	}

	private static JsonNode last(JsonNode points) {
		return points.get(points.size() - 1);
	}

	// passes whose cpuByLoad is on the law at each rate
	private static ObjectNode onTheLaw(List<Double> rates) {
		ArrayNode rows = JsonNodeFactory.instance.arrayNode();
		rates.forEach((rate) -> rows.addObject()
			.put("rateRps", rate)
			.put("passes", 1)
			.put("serviceCpuMsPerRequest", LAW.msAt(rate)));
		return withByLoad(rows);
	}

	private static ArrayNode byCost(double lowRate, double lowMs, double highRate, double highMs) {
		ArrayNode rows = JsonNodeFactory.instance.arrayNode();
		rows.addObject().put("rateRps", lowRate).put("passes", 2).put("serviceCpuMsPerRequest", lowMs);
		rows.addObject().put("rateRps", highRate).put("passes", 2).put("serviceCpuMsPerRequest", highMs);
		return rows;
	}

	private static ObjectNode withByLoad(ArrayNode byLoad) {
		ObjectNode passes = JsonNodeFactory.instance.objectNode();
		passes.set("cpuByLoad", byLoad);
		return passes;
	}

	// the cpuByLoad's rates as 30 s passes, the container's CPU the service's
	private static ObjectNode withStaircase(ObjectNode passes) {
		passes.putObject("idle").put("serviceCpuMsPerSecond", 0.0);
		ArrayNode staircase = passes.putArray("staircase");
		passes.get("cpuByLoad")
			.valueStream()
			.forEach((row) -> staircase.add(pass(row.get("rateRps").doubleValue(),
					row.get("serviceCpuMsPerRequest").doubleValue(), row.get("serviceCpuMsPerRequest").doubleValue())));
		return passes;
	}

	private static ObjectNode staircase(ObjectNode... passes) {
		ObjectNode staircase = JsonNodeFactory.instance.objectNode();
		staircase.putObject("idle").put("serviceCpuMsPerSecond", 0.0);
		staircase.putArray("staircase").addAll(List.of(passes));
		return staircase;
	}

	// a 30 s pass at a rate: the service's CPU per request, and its container's
	private static ObjectNode pass(double rate, double costMs, double containerMs) {
		double requests = rate * 30;
		ObjectNode pass = JsonNodeFactory.instance.objectNode().put("rateRps", rate);
		pass.putObject("service").put("wallMs", 30000.0).put("cpuMs", costMs * requests).put("requests", requests);
		pass.putObject("containerCpuMs").put("api", containerMs * requests);
		return pass;
	}

}
