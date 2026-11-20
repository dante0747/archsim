package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.machine.ContentionFit;
import io.archsim.benchmarks.machine.KnownMachine;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The machine every container shares (W27): a calibration pass's own load on the machine
 * of a contention check gives the design's host, and the CPU-time inputs at full speed.
 * The pass is a pg-mixed top pass: 33 s at 1,600 req/s, the service using 0.32 cores and
 * PostgreSQL 0.28.
 */
class MachineInputsTests {

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	// the machine's speed with the pass's busy threads:
	// the containers, k6 and the background
	private static final double SPEED = FIT
		.speed(0.32 + 0.28 + 1600 * FIT.k6CpuMsPerRequest() / 1000 + FIT.backgroundThreads());

	@Test
	void apply_aPassThatSharedTheMachine_takesTheServiceTimeToFullSpeed() {
		ObjectNode inputs = applied(passes(1600));

		assertThat(SPEED).isLessThan(1);
		assertThat(inputs.at("/serviceTimeMs/value/median").doubleValue()).isCloseTo(0.17 * SPEED, within(1e-6));
		assertThat(inputs.at("/serviceTimeMs/value/p95").doubleValue()).isCloseTo(0.45 * SPEED, within(1e-6));
	}

	@Test
	void apply_aPassThatSharedTheMachine_takesTheDatabasesCpuShareToFullSpeed() {
		ObjectNode inputs = applied(passes(1600));

		assertThat(inputs.at("/dbCpuShare/value").doubleValue()).isCloseTo(0.9 * SPEED, within(1e-4));
	}

	@Test
	void apply_aPassThatSharedTheMachine_saysAtWhatSpeedThePassRan() {
		ObjectNode inputs = applied(passes(1600));

		assertThat(inputs.at("/serviceTimeMs/how").stringValue()).startsWith("cpu; x ")
			.contains(", the machine's thread speed at the pass (");
	}

	// not CPU time
	@Test
	void apply_aPassThatSharedTheMachine_leavesTheLinkLatencyAlone() {
		ObjectNode inputs = applied(passes(1600));

		assertThat(inputs.get("clientLinkLatencyMs")).isEqualTo(inputs().get("clientLinkLatencyMs"));
	}

	@Test
	void apply_aPassThatSharedTheMachine_givesTheDesignItsHost() {
		JsonNode host = applied(passes(1600)).at("/host/value");

		assertThat(host.get("physicalCores").intValue()).isEqualTo(4);
		assertThat(host.get("otherBusyThreads").doubleValue()).isCloseTo(0.4, within(1e-9));
		assertThat(host.get("clientCpuMs").doubleValue()).isCloseTo(FIT.k6CpuMsPerRequest(), within(1e-9));
	}

	// Kafka's broker and consumer, which the model gives no CPU:
	// their CPU at rest as busy threads, the rest per request with k6's
	@Test
	void apply_containersTheModelHasNoCpuFor_joinTheHostsOtherLoad() {
		JsonNode host = applied(kafkaPasses()).at("/host/value");

		assertThat(host.get("otherBusyThreads").doubleValue()).isCloseTo(0.4 + 0.42 + 0.04, within(1e-9));
		assertThat(host.get("clientCpuMs").doubleValue())
			.isCloseTo(FIT.k6CpuMsPerRequest() + (0.9 - 0.42 + 0.3 - 0.04) * 1000 / 600, within(1e-4));
	}

	@Test
	void apply_containersTheModelHasNoCpuFor_areNamed() {
		ObjectNode inputs = applied(kafkaPasses());

		assertThat(inputs.at("/host/how").stringValue()).contains("kafka, consumer (no CPU in the model");
	}

	@Test
	void apply_aPassWithoutItsCpuReadings_changesNothing() {
		ObjectNode passes = passes(1600);
		((ObjectNode) passes.get("high")).remove("containerCpuMs");

		assertThat(applied(passes)).isEqualTo(inputs());
	}

	@Test
	void design_inputsWithAHost_isASchema17DocumentOnThatHost() {
		ObjectNode inputs = applied(passes(1600));
		inputs.putObject("readServiceTimeMs").set("value", JsonText.read("{\"kind\": \"constant\", \"value\": 0.15}"));
		inputs.putObject("writeServiceTimeMs").set("value", JsonText.read("{\"kind\": \"constant\", \"value\": 0.5}"));

		ObjectNode design = Designs.design(PG_MIXED, inputs, PythonValues.integer(1000), Designs.Window.STEP);

		assertThat(design.get("schemaVersion").stringValue()).isEqualTo("1.7");
		assertThat(design.at("/architecture/host")).isEqualTo(inputs.at("/host/value"));
	}

	@Test
	void speedDuring_aPassThatSharedTheMachine_isTheSpeedWithItsBusyThreads() {
		assertThat(MachineInputs.speedDuring(passes(1600).get("high"), FIT).orElseThrow()).isCloseTo(SPEED,
				within(1e-12));
	}

	@Test
	void speedDuring_aPassWithoutItsCpuReadings_isEmpty() {
		ObjectNode passes = passes(1600);
		((ObjectNode) passes.get("high")).remove("containerCpuMs");

		assertThat(MachineInputs.speedDuring(passes.get("high"), FIT)).isEmpty();
	}

	private static ObjectNode applied(ObjectNode passes) {
		ObjectNode inputs = inputs();
		MachineInputs.apply(passes, inputs, FIT);
		return inputs;
	}

	// a top pass at a rate: 33 s with each container's CPU, and the containers at rest
	private static ObjectNode passes(double rate) {
		return (ObjectNode) JsonText.read("""
				{"high": {"rateRps": %s, "service": {"wallMs": 33000.0},
				          "containerCpuMs": {"api": 10560.0, "postgres": 9240.0}},
				 "idle": {"containerCpuMsPerSecond": {"api": 4.0, "postgres": 3.0}}}""".formatted(rate));
	}

	// a Kafka top pass at 600 req/s:
	// the broker busy 0.9 cores (0.42 at rest), the consumer 0.3 (0.04)
	private static ObjectNode kafkaPasses() {
		ObjectNode passes = passes(600);
		((ObjectNode) passes.at("/high/containerCpuMs")).put("kafka", 0.9 * 33000).put("consumer", 0.3 * 33000);
		((ObjectNode) passes.at("/idle/containerCpuMsPerSecond")).put("kafka", 420.0).put("consumer", 40.0);
		return passes;
	}

	private static ObjectNode inputs() {
		return (ObjectNode) JsonText.read("""
				{"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.17, "p95": 0.45}, "how": "cpu"},
				 "dbCpuShare": {"value": 0.9, "how": "share"},
				 "clientLinkLatencyMs": {"value": 0.1, "how": "anchored"}}""");
	}

}
