package io.archsim.engine.simulator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A vCPU counts as a hyperthread by default from schema 1.11 (ADR-0091), and every reader
 * of a part's vCPUs counts it so: the simulation's CPU ceiling, the instant estimate and
 * the design review read the speed the run's resolution wrote into the part, the one a
 * part that sets 0.6 itself has. A design saved before 1.11 is pinned to whole cores and
 * gives what it gave.
 */
class VcpuDefaultTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final RunRequest QUICK_CHECK = RunRequest.of(null).withMode(SimulationMode.SAMPLED);

	/** A service of 4 vCPU on the default, 10 ms of CPU a request. */
	private static final String ON_THE_DEFAULT = "";

	/** The same service setting the default's sibling speed itself. */
	private static final String SETS_THE_DEFAULT = "\"siblingSpeed\": 0.6, ";

	/** A design that sets a sibling speed of a half for every part that sets none. */
	private static final String AT_HALF_SPEED = "\"siblingSpeed\": 0.5, ";

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private final Map<List<String>, JsonNode> reports = new ConcurrentHashMap<>();

	@Test
	void run_aServiceOnTheDefault_hasTheCpuCeilingOfFourVcpuAtTheDefaultSiblingSpeed() {
		// 4 vCPU × 0.6 of a core each, 10 ms a request: 240 requests a second
		assertThat(summary(report("1.11", ON_THE_DEFAULT)).path("cpuCapacityRps").doubleValue()).isEqualTo(240.0);
	}

	@Test
	void run_aServiceOnTheDefault_hasTheCpuCeilingOfAServiceThatSetsTheDefaultsSpeed() {
		assertThat(summary(report("1.11", ON_THE_DEFAULT)).path("cpuCapacityRps"))
			.isEqualTo(summary(report("1.11", SETS_THE_DEFAULT)).path("cpuCapacityRps"));
	}

	@Test
	void run_aServiceOnTheDefault_isEstimatedAsAServiceThatSetsTheDefaultsSpeed() {
		assertThat(estimateOf(report("1.11", ON_THE_DEFAULT))).isEqualTo(estimateOf(report("1.11", SETS_THE_DEFAULT)));
	}

	@Test
	void run_aServiceOnTheDefault_isReviewedAsAServiceThatSetsTheDefaultsSpeed() {
		assertThat(report("1.11", ON_THE_DEFAULT).path("review"))
			.isEqualTo(report("1.11", SETS_THE_DEFAULT).path("review"));
	}

	@Test
	void run_aServiceOnTheDefault_isShortOfCpuInItsReview() {
		// 2.3 cores' work on four hyperthreads keeps more than 80% of them busy; on four
		// whole cores, 58%
		assertThat(cpuFindings(report("1.11", ON_THE_DEFAULT))).isNotEmpty();
	}

	@Test
	void run_aServiceOnTheDefault_recordsTheSpeedItRanAtInItsInput() {
		assertThat(report("1.11", ON_THE_DEFAULT).at("/input/architecture/nodes/1/config/siblingSpeed").doubleValue())
			.isEqualTo(0.6);
	}

	@Test
	void run_aServiceOnTheDefault_saysHowItsVcpusCounted() {
		assertThat(report("1.11", ON_THE_DEFAULT).at("/sizing/nodes/api/vcpus/basis").stringValue())
			.isEqualTo("default");
	}

	@Test
	void run_aServiceOnTheDefault_namesItInTheReportsModelNotes() {
		assertThat(report("1.11", ON_THE_DEFAULT).at("/assumptions/modelNotes")
			.valueStream()
			.map(JsonNode::stringValue)
			.filter((note) -> note.startsWith("vCPUs counted as hyperthread pairs (api)"))).hasSize(1);
	}

	@Test
	void run_aDesignSavedBeforeOnePointEleven_keepsTheCpuCeilingOfWholeCores() {
		assertThat(summary(report("1.10", ON_THE_DEFAULT)).path("cpuCapacityRps").doubleValue()).isEqualTo(400.0);
	}

	@Test
	void run_aDesignSavedBeforeOnePointEleven_isNotShortOfCpuInItsReview() {
		assertThat(cpuFindings(report("1.10", ON_THE_DEFAULT))).isEmpty();
	}

	@Test
	void run_aDesignSavedBeforeOnePointEleven_writesNoSpeedIntoItsParts() {
		assertThat(report("1.10", ON_THE_DEFAULT).at("/input/architecture/nodes/1/config").has("siblingSpeed"))
			.isFalse();
	}

	@Test
	void run_aDesignSavedBeforeOnePointEleven_saysItsVcpusCountedAsTheDesignSets() {
		assertThat(report("1.10", ON_THE_DEFAULT).at("/sizing/nodes/api/vcpus/basis").stringValue())
			.isEqualTo("design");
	}

	@Test
	void run_aQuickCheckAnsweredFromItsSample_saysItsVcpusCountedAsTheDefault() {
		JsonNode report = this.simulator.run(design("1.11", "", ON_THE_DEFAULT, 600), QUICK_CHECK);

		assertThat(List.of(report.at("/simulationMode/kind").stringValue(),
				report.at("/sizing/nodes/api/vcpus/basis").stringValue()))
			.containsExactly("sampled", "default");
	}

	@Test
	void run_aDesignThatSetsASiblingSpeed_hasTheCpuCeilingOfThatSpeed() {
		// 4 vCPU × 0.5 of a core each, 10 ms a request: 200 requests a second
		JsonNode report = report("1.11", AT_HALF_SPEED, ON_THE_DEFAULT);

		assertThat(summary(report).path("cpuCapacityRps").doubleValue()).isEqualTo(200.0);
	}

	@Test
	void run_aDesignThatSetsASiblingSpeed_recordsItInItsPartsInput() {
		JsonNode report = report("1.11", AT_HALF_SPEED, ON_THE_DEFAULT);

		assertThat(report.at("/input/architecture/nodes/1/config/siblingSpeed").doubleValue()).isEqualTo(0.5);
	}

	@Test
	void run_aQuickCheckOfADesignThatSetsASiblingSpeed_saysItsVcpusCountedAsTheDesignSets() {
		// at 0.8 the service keeps up, so the sample is steady and answers for the run
		JsonNode report = this.simulator.run(design("1.11", "\"siblingSpeed\": 0.8, ", ON_THE_DEFAULT, 600),
				QUICK_CHECK);

		assertThat(List.of(report.at("/simulationMode/kind").stringValue(),
				report.at("/sizing/nodes/api/vcpus/basis").stringValue()))
			.containsExactly("sampled", "design");
	}

	private JsonNode report(String version, String settings) {
		return report(version, "", settings);
	}

	private JsonNode report(String version, String architecture, String settings) {
		return this.reports.computeIfAbsent(List.of(version, architecture, settings),
				(_) -> this.simulator.run(design(version, architecture, settings, 20), RunRequest.of(null)));
	}

	/**
	 * Clients calling a service of 4 vCPU at 230 requests a second, each 10 ms of CPU:
	 * 2.3 cores' work.
	 * @param architecture more of the architecture's settings, each followed by a comma
	 * @param settings more of the service's settings, each followed by a comma
	 */
	private static JsonNode design(String version, String architecture, String settings, int durationS) {
		return JSON.readTree("""
				{"schemaVersion": "%s", "kind": "archsim.architecture", "metadata": {"name": "vcpus"},
				 "architecture": {%s"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 32, %s
				      "cpuCores": 4, "serviceTimeMs": {"kind": "constant", "value": 10}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 230, "durationS": %d, "warmupS": 2, "arrivalProcess": "deterministic"}}"""
			.formatted(version, architecture, settings, durationS));
	}

	private static JsonNode summary(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "api");
	}

	// the service's row of the instant estimate
	private static JsonNode estimateOf(JsonNode report) {
		return report.path("estimate")
			.path("parts")
			.valueStream()
			.filter((part) -> "api".equals(part.path("id").stringValue("")))
			.findFirst()
			.orElseThrow();
	}

	private static List<String> cpuFindings(JsonNode report) {
		return report.required("review")
			.valueStream()
			.filter((finding) -> finding.path("rule").stringValue().equals("cpu-capacity"))
			.map((finding) -> finding.path("title").stringValue())
			.toList();
	}

}
