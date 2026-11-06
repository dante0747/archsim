package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * Something a run found.
 *
 * @param kind what kind of finding, such as {@code bottleneck}
 * @param nodeId the part it is about, if one
 * @param title what it found, in a few words
 */
public record ReportFinding(@Nullable String kind, @Nullable String nodeId, @Nullable String title) {

}
