package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;

/**
 * Findings about how a run changed over time: faults, autoscaling, serverless, queues.
 */
final class DynamicsFindings {

	private DynamicsFindings() {
	}

	/**
	 * The time-varying findings of a run.
	 * @param run the run
	 * @return each fault's impact, then the services' scaling, then each queue's lag;
	 * none for a run without time buckets
	 * @throws ArithmeticException when the report's buckets have no length
	 */
	static List<RunFinding> of(RunContext run) {
		if (run.report().timeseries().t().isEmpty()) {
			return List.of();
		}
		ClientSeries series = new ClientSeries(run.report());
		List<RunFinding> findings = new ArrayList<>(FaultFindings.of(run, series));
		findings.addAll(ServiceFindings.of(run, series));
		findings.addAll(QueueFindings.of(run.report()));
		return findings;
	}

}
