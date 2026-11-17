package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.report.RunReport;
import io.archsim.engine.slo.SloResult;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * What a check measures of a run: it needs the design the run simulated, and keeps its
 * own copy of the loads it swept.
 */
class MeasurementTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DESIGN = """
			{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"}, {"id": "api", "type": "service"}],
			                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
			 "workload": {"rps": 100}}""";

	private static final String REPORT_WITHOUT_INPUT = """
			{"summary": {"latencyMs": {"p95": 90.0}, "failureBreakdown": []}, "nodes": [], "findings": [],
			 "provenance": {"resultDigest": "f"}, "timeseries": {"bucketS": 1.0},
			 "window": {"warmupS": 0.0, "durationS": 10.0, "measuredS": 10.0}}""";

	@Test
	void of_aReportWithoutTheDesignItRan_isRefused() {
		ArchitectureDocument design = DesignDocuments.parse(JSON.readTree(DESIGN));
		RunReport report = RunReport.read(JSON.readTree(REPORT_WITHOUT_INPUT));

		assertThatNullPointerException().isThrownBy(() -> Measurement.of(design, null, report))
			.withMessage("a run's report has the design it ran");
	}

	@Test
	void kneeRange_changedAfterwards_staysAsItWas() {
		List<Double> range = new ArrayList<>(List.of(200.0, 3000.0));
		Measurement measurement = new Measurement("case", null, 90.0, null, 1.0, 400.0, 400.0, null,
				new Objectives(300.0, null, 0.999, null, null), new SloResult(List.of(), null, 0, 0), "No objectives",
				null, "f", "0.16.0", new MeasuredConfidence(null, null, null), 1000.0, "Breaks at 1,000 req/s", range,
				100.0);

		range.add(4000.0);

		assertThat(measurement.kneeRange()).containsExactly(200.0, 3000.0);
	}

}
