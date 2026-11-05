package io.archsim.engine.report;

import java.util.Objects;

/**
 * What a run was.
 *
 * @param resultDigest the digest of its result (its summary, time series, parts, links
 * and window), the same in either engine for the same result
 */
public record ReportProvenance(String resultDigest) {

	/**
	 * Checks that the provenance has the result's digest, as every report's has.
	 */
	public ReportProvenance {
		Objects.requireNonNull(resultDigest, "a report's provenance has its result's digest");
	}

}
