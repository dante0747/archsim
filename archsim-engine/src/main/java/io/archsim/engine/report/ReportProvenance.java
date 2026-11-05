package io.archsim.engine.report;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * What a run was.
 *
 * @param engineVersion the version of the engine that ran it; {@code null} when the
 * report does not say
 * @param seed the seed its random numbers came from; {@code null} when the report does
 * not say
 * @param resultDigest the digest of its result (its summary, time series, parts, links
 * and window), the same in either engine for the same result
 */
public record ReportProvenance(@Nullable String engineVersion, @Nullable Long seed, String resultDigest) {

	/**
	 * Checks that the provenance has the result's digest, as every report's has.
	 */
	public ReportProvenance {
		Objects.requireNonNull(resultDigest, "a report's provenance has its result's digest");
	}

}
