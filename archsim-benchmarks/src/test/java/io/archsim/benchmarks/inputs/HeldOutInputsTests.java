package io.archsim.benchmarks.inputs;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The held-out experiments' inputs and designs from two calibration passes
 * ({@link HeldOutPasses}): each part's inputs, and each stack's nodes and links.
 */
class HeldOutInputsTests {

	private static final ModelInputs INPUTS = new ModelInputs(Simulations.onTheEngine());

	private static final Designs.Window BRIEF = new Designs.Window(1, 3);

	// the slope between the passes: (4500 - 900) / (12000 - 1500)
	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl" })
	void of_aHeldOutExperiment_takesTheServicesCpuPerExtraRequest(String experiment) {
		ObjectNode inputs = inputs(experiment);

		assertThat(inputs.at("/serviceTimeMs/how").stringValue()).contains("0.3429 ms");
		assertThat(inputs.properties())
			.allSatisfy((input) -> assertThat(!input.getValue().has("how") || input.getValue().has("value")).isTrue());
	}

	// two passes and no rest (before protocol v5): no CPU figure for the writes without
	// their flush, so PostgreSQL's times are the round trips, with no link to add
	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain" })
	void of_aStackWithPostgresql_takesTheRoundTripsAsPostgresqlsTimes(String experiment) {
		ObjectNode inputs = inputs(experiment);

		assertThat(List.of(inputs.at("/readServiceTimeMs/value/median").doubleValue(),
				inputs.at("/writeServiceTimeMs/value/median").doubleValue(),
				inputs.at("/dbLinkLatencyMs/value").doubleValue()))
			.containsExactly(0.6, 0.8, 0.0);
		assertThat(inputs.at("/dbCpuShare/value").doubleValue()).isPositive();
	}

	// the commit's wait, 2.0 - 0.8 ms, less what the model's writes queue at 400 req/s
	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain" })
	void of_aStackWithPostgresql_fitsAFlushShorterThanTheCommitsWait(String experiment) {
		double flush = inputs(experiment).at("/commitFlushMs/value/median").doubleValue();

		assertThat(flush).isCloseTo(1.2, within(0.05 * 1.2)).isLessThan(1.2);
	}

	// 18 ms over 6 collections: a 3 ms mean, the
	// model's median 3 / 1.17; 12,000 requests / 6
	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed" })
	void of_aJavaOrGoService_modelsItsStopTheWorldPauses(String experiment) {
		ObjectNode inputs = inputs(experiment);

		assertThat(inputs.at("/gcPauseMs/value").doubleValue()).isCloseTo(3 / 1.1677, within(1e-3 * 3 / 1.1677));
		assertThat(inputs.at("/gcEveryRequests/value").intValue()).isEqualTo(2000);
	}

	@Test
	void of_aPythonService_modelsNoPause() {
		ObjectNode inputs = inputs("python-mixed");

		assertThat(inputs.at("/gcPauseMs/value").doubleValue()).isZero();
		assertThat(inputs.has("gcEveryRequests")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "chain", "cache-ttl" })
	void of_aNodeServiceThatReportedNoGc_modelsNone(String experiment) {
		assertThat(inputs(experiment).has("gcPauseMs")).isFalse();
	}

	// the API's slope: (3600 - 720) / (12000 -
	// 1500); the link: (1.0 - 1.2) / 2, at least 0
	@Test
	void of_chain_takesTheApisCpuAndLinkBehindTheGateway() {
		ObjectNode inputs = inputs("chain");

		assertThat(inputs.at("/apiServiceTimeMs/how").stringValue()).contains("0.2743 ms");
		assertThat(inputs.at("/apiLinkLatencyMs/value").doubleValue()).isZero();
	}

	// Redis's own time from the service's /stats: (36,000 + 12,000) us / 16,000 calls
	@Test
	void of_cacheTtl_takesRedissOwnTimeFromTheServicesStats() {
		assertThat(inputs("cache-ttl").at("/cacheServiceTimeMs/value/mean").doubleValue()).isEqualTo(0.003);
	}

	@Test
	void runtime_aServiceWithoutACollection_modelsNoPause() {
		ObjectNode none = GcInputs.runtime(experiment("java-mixed"),
				JsonText.read("{\"requests\": 100, \"gc\": {\"collections\": 0, \"pauseMs\": 0.0}}"));

		assertThat(none.at("/gcPauseMs/value").doubleValue()).isZero();
		assertThat(none.has("gcEveryRequests")).isFalse();
	}

	@Test
	void runtime_aServiceThatReportedNoGc_modelsNoPause() {
		ObjectNode none = GcInputs.runtime(experiment("java-mixed"), JsonText.read("{\"requests\": 100}"));

		assertThat(none.at("/gcPauseMs/value").doubleValue()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl" })
	void design_aHeldOutExperiment_hasItsTrafficsKeysSkewAndMix(String experiment) {
		JsonNode workload = design(experiment).get("workload");

		Experiment held = experiment(experiment);
		assertThat(List.of(workload.get("keyCount").intValue(), workload.get("keySkew").doubleValue(),
				workload.get("readRatio").doubleValue()))
			.containsExactly(held.keys(), held.skew(), held.readRatio());
	}

	@ParameterizedTest
	@CsvSource({ "java-mixed, java, 200", "go-mixed, go, 4096", "python-mixed, python, 10" })
	void design_aJavaGoOrPythonService_isAPoolOfWorkersThatWaitForPostgresql(String experiment, String technology,
			int workers) {
		JsonNode api = node(design(experiment), "api");

		assertThat(List.of(api.get("technology").stringValue(), api.get("workersPerReplica").intValue(),
				api.get("cpuCores").intValue(), api.get("blockingDownstream").booleanValue()))
			.containsExactly(technology, workers, 2, true);
		assertThat(api.get("gcPauseMs")).isEqualTo(inputs(experiment).at("/gcPauseMs/value"));
		assertThat(api.has("processesPerReplica")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "chain", "cache-ttl" })
	void design_aNodeService_isOneEventLoopOnOneCpu(String experiment) {
		JsonNode api = node(design(experiment), "api");

		assertThat(List.of(api.get("technology").stringValue(), api.get("processesPerReplica").intValue(),
				api.get("cpuCores").intValue()))
			.containsExactly("nodejs", 1, 1);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "java-mixed | clients>api api>db", "go-mixed | clients>api api>db",
					"python-mixed | clients>api api>db", "chain | clients>gateway gateway>api api>db",
					"cache-ttl | clients>api api>cache cache>db" })
	void design_aHeldOutExperiment_linksItsStacksNodesInOrder(String experiment, String links) {
		JsonNode edges = design(experiment).at("/architecture/edges");

		assertThat(String.join(" ",
				edges.valueStream()
					.map((edge) -> edge.get("source").stringValue() + ">" + edge.get("target").stringValue())
					.toList()))
			.isEqualTo(links);
	}

	@Test
	void design_chain_givesTheGatewayAndTheApiTheirOwnServiceTimes() {
		ObjectNode inputs = inputs("chain");
		ObjectNode design = design("chain");

		assertThat(node(design, "gateway").get("serviceTimeMs")).isEqualTo(inputs.at("/serviceTimeMs/value"));
		assertThat(node(design, "api").get("serviceTimeMs")).isEqualTo(inputs.at("/apiServiceTimeMs/value"));
	}

	@Test
	void design_cacheTtl_expiresItsKeysAfterFiveSeconds() {
		assertThat(node(design("cache-ttl"), "cache").get("ttlSeconds").intValue()).isEqualTo(5);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("busyNodes")
	void simulate_aHeldOutDesign_servesEveryRequestOnBusyNodes(String experiment, List<String> busy) {
		JsonNode simulated = Simulations.onTheEngine().simulate(design(experiment));

		assertThat(simulated.get("successRate").doubleValue()).isEqualTo(1.0);
		assertThat(simulated.get("p50").doubleValue()).isPositive();
		assertThat(busy)
			.allSatisfy((name) -> assertThat(simulated.at("/resources/" + name + "/cores").doubleValue()).isPositive());
	}

	static Stream<Arguments> busyNodes() {
		return Stream.of(Arguments.of("java-mixed", List.of("api", "db")),
				Arguments.of("go-mixed", List.of("api", "db")), Arguments.of("python-mixed", List.of("api", "db")),
				Arguments.of("chain", List.of("api", "db", "gateway")),
				Arguments.of("cache-ttl", List.of("api", "db")));
	}

	@Test
	void simulate_cacheTtl_missesSomeKeys() {
		JsonNode simulated = Simulations.onTheEngine().simulate(design("cache-ttl"));

		assertThat(simulated.get("cacheHitRatio").doubleValue()).isStrictlyBetween(0.0, 1.0);
	}

	private static Experiment experiment(String id) {
		return Experiments.standard().get(id);
	}

	private static ObjectNode inputs(String experiment) {
		return INPUTS.of(experiment(experiment), HeldOutPasses.of(experiment(experiment)));
	}

	// the design at 100 req/s, briefly, with a client latency of 0.2 ms
	private static ObjectNode design(String experiment) {
		ObjectNode inputs = inputs(experiment);
		((ObjectNode) inputs.get("clientLinkLatencyMs")).put("value", 0.2);
		return Designs.design(experiment(experiment), inputs, JsonText.read("100"), BRIEF);
	}

	private static JsonNode node(JsonNode design, String id) {
		return design.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow()
			.get("config");
	}

}
