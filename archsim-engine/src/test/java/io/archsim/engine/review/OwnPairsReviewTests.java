package io.archsim.engine.review;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design review's CPU check of a part on its own hyperthread pairs (ADR-0091): its
 * CPU fills where every vCPU is busy at the sibling speed, so the review judges it by the
 * share of that ceiling the peak takes, as the instant estimate's load does, and says so;
 * past its vCPUs its work waits for one, which that share past 1 says. A service of 4
 * vCPU at the default 0.6 doing 10 ms of CPU a request fills its vCPUs at 240 req/s.
 */
class OwnPairsReviewTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A host machine of 8 idle cores, 16 threads. */
	private static final String IDLE_HOST = """
			, "host": {"physicalCores": 8, "threadsPerCore": 2, "siblingSpeed": 0.6}""";

	private final ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private final DesignReview review = new DesignReview(StandardComponents.registry());

	@Test
	void review_anOverloadedServiceOnItsOwnPairs_needsTheShareOfItsCeilingThePeakTakes() {
		// 300 requests a second of 10 ms are 3 cores' work; 4 vCPU at 0.6 do 2.4
		List<Finding> findings = cpuFindings(service("1.11", 300, "", ""));

		assertThat(findings).singleElement().satisfies((finding) -> {
			assertThat(finding.severity()).isEqualTo(Severity.HIGH);
			assertThat(finding.detail())
				.contains("so the peak would take 125% of its CPU's ceiling and requests queue for a core.");
		});
	}

	@Test
	void review_aServiceAtFiveSixthsOfItsCeiling_hasLittleCpuHeadroom() {
		// 2 cores' work, while some siblings idle, of the 2.4 its vCPUs do at 0.6
		List<Finding> findings = cpuFindings(service("1.11", 200, "", ""));

		assertThat(findings).singleElement().satisfies((finding) -> {
			assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
			assertThat(finding.detail()).contains("handle about 400 req/s at full speed; with every vCPU busy on its "
					+ "hyperthreads they run at 0.60 of it, so the peak would take 83% of its CPU's ceiling.");
		});
	}

	@Test
	void review_aServiceJustUnderItsCeiling_hasLittleCpuHeadroom() {
		List<Finding> findings = cpuFindings(service("1.11", 220, "", ""));

		assertThat(findings).singleElement().satisfies((finding) -> {
			assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
			assertThat(finding.detail()).contains("so the peak would take 92% of its CPU's ceiling.");
		});
	}

	@Test
	void review_aDatabaseAtFiveSixthsOfItsCeiling_hasLittleCpuHeadroom() {
		// each request's 10 ms query: 2 cores' work of the 2.4 its 4 vCPU do at 0.6
		List<Finding> findings = cpuFindings(DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "pairs"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 64,
				      "cpuCores": 1, "serviceTimeMs": {"kind": "constant", "value": 1}}},
				    {"id": "db", "type": "database", "config": {"cpuCores": 4, "cpuShare": 1,
				      "readServiceTimeMs": {"kind": "constant", "value": 10},
				      "writeServiceTimeMs": {"kind": "constant", "value": 10}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-db", "source": "api", "target": "db"}]},
				 "workload": {"rps": 200}, "pricing": {"provider": "generic"}}""")));

		assertThat(findings).singleElement().satisfies((finding) -> {
			assertThat(finding.nodeIds()).containsExactly("db");
			assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
			assertThat(finding.detail()).contains("so the peak would take 83% of its CPU's ceiling.");
		});
	}

	@Test
	void review_aServiceOfADesignSavedBeforeOnePointEleven_isJudgedOnWholeCores() {
		// the upgrade pins the design to whole cores: 2.2 cores' work of 4
		List<Finding> findings = cpuFindings(service("1.10", 220, "", ""));

		assertThat(findings).isEmpty();
	}

	@Test
	void review_aServiceOnAHostMachine_isJudgedAtTheSpeedItsThreadsRunAt() {
		// its threads share the host's idle cores, not each other's
		List<Finding> findings = cpuFindings(service("1.11", 200, ", \"siblingSpeed\": 0.6", IDLE_HOST));

		assertThat(findings).isEmpty();
	}

	private List<Finding> cpuFindings(ArchitectureDocument design) {
		ArchitectureDocument resolved = this.resolver.resolve(design, null).document();
		return this.review.review(resolved)
			.stream()
			.filter((finding) -> finding.rule() == ReviewRule.CPU_CAPACITY)
			.toList();
	}

	/**
	 * Clients calling a service of 4 vCPU doing 10 ms of CPU a request.
	 * @param version the design's schema version
	 * @param rps the peak, in requests per second
	 * @param settings more of the service's settings, each after a comma
	 * @param host the design's host machine, after a comma; empty for none
	 */
	private static ArchitectureDocument service(String version, int rps, String settings, String host) {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "%s", "kind": "archsim.architecture", "metadata": {"name": "pairs"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 64,
				      "cpuCores": 4, "serviceTimeMs": {"kind": "constant", "value": 10}%s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]%s},
				 "workload": {"rps": %d}, "pricing": {"provider": "generic"}}""".formatted(version, settings, host,
				rps)));
	}

}
