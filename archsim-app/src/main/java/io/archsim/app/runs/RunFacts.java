package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;

/**
 * A run as other features see it: where it is, how far it got and what it simulated.
 *
 * @param id the run's id
 * @param projectId its project
 * @param label what people call it, if anything
 * @param status {@code queued}, {@code running}, {@code succeeded}, {@code failed} or
 * {@code cancelled}
 * @param inputDigest the digest of the design it simulated
 * @param resultDigest the digest of its report, once it succeeded
 */
public record RunFacts(String id, String projectId, @Nullable String label, String status, @Nullable String inputDigest,
		@Nullable String resultDigest) {

	static RunFacts of(StoredRun run) {
		RunView view = run.view();
		return new RunFacts(view.id(), view.projectId(), view.label(), view.status(), view.inputDigest(),
				view.resultDigest());
	}

	/**
	 * Whether the run finished and has its report.
	 * @return {@code true} for a run that succeeded
	 */
	public boolean succeeded() {
		return RunView.SUCCEEDED.equals(this.status);
	}

}
