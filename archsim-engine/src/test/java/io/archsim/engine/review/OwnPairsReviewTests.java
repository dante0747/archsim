package io.archsim.engine.review;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design review's CPU check of a part on its own hyperthread pairs (ADR-0091): past
 * its vCPUs its work runs at the sibling speed and waits for a vCPU, which its CPU share
 * past 1 says, rather than at the speed of vCPUs taking turns at it.
 */
class OwnPairsReviewTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DesignReview review = new DesignReview(StandardComponents.registry());

	@Test
	void review_anOverloadedServiceOnItsOwnPairs_needsTheShareOfItsCeilingThePeakTakes() {
		// 300 requests a second of 10 ms are 3 cores' work; 4 vCPU at 0.6 do 2.4
		List<Finding> findings = this.review.review(DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "pairs"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 64,
				      "cpuCores": 4, "siblingSpeed": 0.6, "serviceTimeMs": {"kind": "constant", "value": 10}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 300}}""")));

		assertThat(findings).filteredOn((finding) -> finding.rule() == ReviewRule.CPU_CAPACITY)
			.singleElement()
			.satisfies((finding) -> assertThat(finding.detail()).contains("its CPU would be 125% busy"));
	}

}
