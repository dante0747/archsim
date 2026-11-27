package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;

/**
 * What editors do to a run: stop it, delete it, or run it again to check it reproduces.
 */
@Service
class RunChanges {

	private final Runs runs;

	private final RunPlanner planner;

	private final RunAdmission admission;

	private final RunQueue queue;

	RunChanges(Runs runs, RunPlanner planner, RunAdmission admission, RunQueue queue) {
		this.runs = runs;
		this.planner = planner;
		this.admission = admission;
		this.queue = queue;
	}

	/**
	 * Simulates a run's stored input again (same design, scenario, seed and mode): the
	 * new run's {@code resultDigest} should equal the original's when the same engine
	 * simulates it (ADR-0050).
	 * @param principal who asks
	 * @param runId the run to reproduce
	 * @return the new run, naming the one it reproduces
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 429} over the hourly limits
	 */
	RunView rerun(Principal principal, String runId) {
		StoredRun original = this.runs.require(principal, runId, WorkspaceRole.EDITOR);
		RunView view = original.view();
		RunPlanner.Plan plan = this.planner.plan(original.workspaceId());
		PlannedRun prepared = this.planner
			.prepare(plan.request(this.runs.inputDocument(original), view.scenarioId(), view.seed()));
		Destination destination = new Destination(view.projectId(), original.workspaceId(), plan.limits());
		NewRun run = new NewRun(destination, prepared, RunLabels.cut("Reproduce: " + view.name()),
				RunMode.of(view.mode()));
		return this.admission.admit(plan.workspace(), principal, List.of(run)).getFirst().reproducing(view.id());
	}

	/**
	 * Stops a queued or running run; a finished one is left as it is.
	 * @param principal who asks
	 * @param runId the run
	 * @return the run as it is now
	 */
	RunView cancel(Principal principal, String runId) {
		return this.queue.cancel(this.runs.require(principal, runId, WorkspaceRole.EDITOR), principal);
	}

	/**
	 * Deletes a run, stopping it first.
	 * @param principal who asks
	 * @param runId the run
	 */
	void delete(Principal principal, String runId) {
		this.queue.delete(this.runs.require(principal, runId, WorkspaceRole.EDITOR), principal);
	}

}
