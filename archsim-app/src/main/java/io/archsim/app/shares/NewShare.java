package io.archsim.app.shares;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.runs.RunFacts;

/**
 * A share link to make.
 *
 * @param workspaceId the workspace it is made in, the run's
 * @param run the run it shares
 * @param title what the page shows
 * @param payload the frozen copy of the run's results
 * @param reportVersion the version of the report it holds
 * @param expiresInDays days until it stops working; {@code null} for never
 */
record NewShare(String workspaceId, RunFacts run, String title, ObjectNode payload, String reportVersion,
		@Nullable Integer expiresInDays) {

}
