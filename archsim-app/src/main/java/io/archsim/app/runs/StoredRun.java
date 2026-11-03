package io.archsim.app.runs;

/**
 * A run as stored: what people read, and the workspace that decides who may.
 *
 * @param workspaceId the run's workspace, its project's
 * @param view the run
 */
record StoredRun(String workspaceId, RunView view) {

	String id() {
		return this.view.id();
	}

}
