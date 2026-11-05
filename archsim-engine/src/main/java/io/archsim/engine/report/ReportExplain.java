package io.archsim.engine.report;

import java.util.List;

/**
 * How a run's report explains its result, as far as the engine reads it.
 *
 * @param latencyBreakdown where a traced request's time goes, in the report's order
 */
public record ReportExplain(List<LatencyShare> latencyBreakdown) {

	/**
	 * Copies the breakdown; a report without one has none.
	 */
	public ReportExplain {
		latencyBreakdown = (latencyBreakdown != null) ? List.copyOf(latencyBreakdown) : List.of();
	}

}
