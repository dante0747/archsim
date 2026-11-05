package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * Where and why some of a run's requests failed.
 *
 * @param count how many requests failed this way
 * @param edgeId the link they failed on, if one
 * @param nodeId the part they failed at
 * @param nodeLabel that part's name
 * @param reason why they failed, such as {@code client_timeout}
 * @param reasonText why, in words
 * @param share their share of all failures
 * @param status the status they failed with, such as {@code timeout}
 */
public record FailureCause(long count, @Nullable String edgeId, @Nullable String nodeId, @Nullable String nodeLabel,
		@Nullable String reason, @Nullable String reasonText, @Nullable Double share, @Nullable String status) {

}
