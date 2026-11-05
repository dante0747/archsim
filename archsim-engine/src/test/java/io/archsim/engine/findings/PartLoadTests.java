package io.archsim.engine.findings;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a part's load reads: an unmeasured utilization reads as zero, the calls an injected
 * outage refused are the outage, not overload, and a queue kept growing when its last
 * quarter is well above its first.
 */
class PartLoadTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void loadRejectionRate_callsRefusedInAnOutage_countNeitherAsRejectedNorAsServed() {
		NodeSummary summary = summary("{\"arrivals\": 1100, \"rejectedTotal\": 150, \"rejected\": {\"outage\": 100}}");

		assertThat(PartLoad.loadRejectionRate(summary)).isEqualTo(50.0 / 1000);
	}

	@Test
	void outageRefusals_callsRefusedInAnOutage_areTheOutageRejections() {
		NodeSummary summary = summary("{\"arrivals\": 1100, \"rejectedTotal\": 150, \"rejected\": {\"outage\": 100}}");

		assertThat(PartLoad.outageRefusals(summary)).isEqualTo(100);
	}

	@Test
	void utilization_notMeasured_isZero() {
		assertThat(PartLoad.utilization(summary("{\"arrivals\": 10}"))).isZero();
	}

	@Test
	void loadRejectionRate_noCallReachedItUp_isZero() {
		assertThat(PartLoad.loadRejectionRate(summary("{\"arrivals\": 10, \"rejected\": {\"outage\": 10}}"))).isZero();
	}

	@Test
	void queueGrowing_aLastQuarterWellAboveTheFirst_keptGrowing() {
		assertThat(PartLoad.queueGrowing(series(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), 0, 8)).isTrue();
	}

	@Test
	void queueGrowing_aSmallQueue_didNot() {
		assertThat(PartLoad.queueGrowing(series(0.0, 0.0, 1.0, 1.9), 0, 4)).isFalse();
	}

	@Test
	void queueGrowing_fewerThanFourBuckets_cannotSay() {
		assertThat(PartLoad.queueGrowing(series(0.0, 50.0, 100.0), 0, 3)).isFalse();
	}

	@Test
	void queueGrowing_gaps_readAsEmpty() {
		assertThat(PartLoad.queueGrowing(series(null, null, 9.0, 9.0), 0, 4)).isTrue();
	}

	private static NodeSummary summary(String json) {
		return ReportNode.read(JSON.readTree("{\"id\": \"api\", \"type\": \"service\", \"category\": \"compute\", "
				+ "\"label\": \"API\", \"summary\": " + json + "}"))
			.summary();
	}

	private static List<@Nullable Double> series(@Nullable Double... values) {
		return Arrays.asList(values);
	}

}
