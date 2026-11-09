package io.archsim.app.worker;

/**
 * A queued run a claim may take.
 *
 * @param runId the run
 * @param workspaceId the workspace it belongs to
 */
record Candidate(String runId, String workspaceId) {

}
