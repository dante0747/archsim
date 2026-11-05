package io.archsim.engine.report;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One link of the design in a run's report.
 *
 * @param id the link's id
 * @param callRps the calls per second it carried; {@code null} when the report does not
 * say
 */
public record ReportEdge(String id, @Nullable Double callRps) {

	/**
	 * Checks that the link has its id.
	 */
	public ReportEdge {
		Objects.requireNonNull(id, "a report's link has its id");
	}

}
