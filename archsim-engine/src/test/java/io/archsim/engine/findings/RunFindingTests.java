package io.archsim.engine.findings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A run's finding as a report writes it, and its evidence, which does not change. */
class RunFindingTests {

	@Test
	void toJson_aFindingWithoutASuggestion_leavesItOut() {
		RunFinding finding = new RunFinding(FindingSeverity.OK, FindingKind.HEALTHY, null, "No component is saturated",
				"Throughput tracks the offered load.", null, RunFinding.emptyEvidence());

		assertThat(finding.toJson().propertyNames()).containsExactly("severity", "kind", "nodeId", "title", "detail",
				"evidence");
	}

	@Test
	void toJson_aSeverity_isWrittenAsTheReportWritesIt() {
		RunFinding finding = new RunFinding(FindingSeverity.OK, FindingKind.HEALTHY, null, "No component is saturated",
				"Throughput tracks the offered load.", null, RunFinding.emptyEvidence());

		assertThat(finding.toJson().path("severity").stringValue()).isEqualTo("ok");
	}

	@Test
	void evidence_changedAfterTheFindingIsMade_staysAsItWas() {
		RunFinding finding = new RunFinding(FindingSeverity.INFO, FindingKind.CACHE, "cache", "Cache hit ratio 80%",
				"80% of reads were served from the cache.", null, RunFinding.emptyEvidence().put("hitRatio", 0.8));

		finding.evidence().put("hitRatio", 0.1);

		assertThat(finding.evidence().path("hitRatio").doubleValue()).isEqualTo(0.8);
	}

}
