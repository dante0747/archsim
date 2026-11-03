package io.archsim.app.runs;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;

/**
 * Finding a run for someone: a run belongs to its project's workspace, and someone with
 * no role there gets the same {@code 404} as for a run that does not exist.
 */
@Service
public class RunLookup {

	private final RunRepository repository;

	private final Access access;

	RunLookup(RunRepository repository, Access access) {
		this.repository = repository;
		this.access = access;
	}

	/**
	 * A run, if the principal may act on it.
	 * @param principal who is asking
	 * @param runId the run
	 * @param minimum the role the action needs
	 * @return the run
	 * @throws ApiException {@code 404} without a role in its workspace, {@code 403} with
	 * one too weak
	 */
	public RunFacts require(Principal principal, String runId, WorkspaceRole minimum) {
		return RunFacts.of(stored(principal, runId, minimum));
	}

	/**
	 * Whether a run is one of a project's.
	 * @param runId the run
	 * @param projectId the project
	 * @return {@code true} when the run exists and is the project's
	 */
	public boolean inProject(String runId, String projectId) {
		return this.repository.find(runId).filter((run) -> run.view().projectId().equals(projectId)).isPresent();
	}

	/**
	 * A run as stored, if the principal may act on it.
	 * @param principal who is asking
	 * @param runId the run
	 * @param minimum the role the action needs
	 * @return the run
	 * @throws ApiException {@code 404} without a role in its workspace, {@code 403} with
	 * one too weak
	 */
	StoredRun stored(Principal principal, String runId, WorkspaceRole minimum) {
		String what = "Run '" + runId + "'";
		StoredRun run = this.repository.find(runId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, run.workspaceId(), minimum, what);
		return run;
	}

}
