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
 * and window), which names a result within one engine: a Java run and a Python run of the
 * same input and seed agree to the parity bar but need not share it (ADR-0050)
 * @param java the version of the Java runtime that simulated it (report 1.15 on);
 * {@code null} when the Python engine did
 */
public record ReportProvenance(@Nullable String engineVersion, @Nullable Long seed, String resultDigest,
		@Nullable String java) {

	/**
	 * Checks that the provenance has the result's digest, as every report's has.
	 */
	public ReportProvenance {
		Objects.requireNonNull(resultDigest, "a report's provenance has its result's digest");
	}

}
