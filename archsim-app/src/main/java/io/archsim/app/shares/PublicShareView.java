package io.archsim.app.shares;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A shared report, as anyone with the link sees it.
 *
 * @param slug the link's id
 * @param title what the page shows
 * @param projectName the project's name when the link was made
 * @param runLabel the run's label
 * @param scenarioName the scenario it simulated
 * @param createdAt when the link was made
 * @param expiresAt when it stops working, if ever
 * @param payloadVersion the version of the copy's shape
 * @param reportVersion the version of the report
 * @param resultDigest the run's result digest
 * @param objectives the design's objectives
 * @param report the report, without its request traces
 * @param canCopy whether "Open a copy" can make a project from it (it holds the design)
 */
record PublicShareView(String slug, String title, String projectName, @Nullable String runLabel,
		@Nullable String scenarioName, Instant createdAt, @Nullable Instant expiresAt, int payloadVersion,
		String reportVersion, @Nullable String resultDigest, JsonNode objectives, JsonNode report, boolean canCopy) {

}
