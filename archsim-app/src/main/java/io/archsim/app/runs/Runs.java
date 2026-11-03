package io.archsim.app.runs;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;

/**
 * Runs as people read them. A run belongs to its project's workspace: viewers read runs;
 * someone with no role there gets the same {@code 404} as for a run that does not exist.
 */
@Service
class Runs {

	private final RunRepository repository;

	private final ProjectService projects;

	private final RunLookup lookup;

	private final ArchsimProperties properties;

	Runs(RunRepository repository, ProjectService projects, RunLookup lookup, ArchsimProperties properties) {
		this.repository = repository;
		this.projects = projects;
		this.lookup = lookup;
		this.properties = properties;
	}

	/**
	 * A project's latest runs ({@code ARCHSIM_RUN_HISTORY_LIMIT} at most).
	 * @param principal who is asking
	 * @param projectId the project
	 * @return the runs, newest first
	 */
	List<RunView> list(Principal principal, String projectId) {
		this.projects.requireAccess(principal, projectId, WorkspaceRole.VIEWER);
		return this.repository.ofProject(projectId, this.properties.runHistoryLimit());
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
	StoredRun require(Principal principal, String runId, WorkspaceRole minimum) {
		return this.lookup.stored(principal, runId, minimum);
	}

	/**
	 * A finished run's report: provenance, summary, time series, per-node metrics,
	 * findings.
	 * @param run the run
	 * @return the report, as stored
	 * @throws ApiException {@code 409} until the run succeeded
	 */
	JsonText report(StoredRun run) {
		String status = run.view().status();
		return this.repository.report(run.id())
			.filter((report) -> run.view().succeeded())
			.map(JsonText::new)
			.orElseThrow(() -> ApiException.conflict("Run is " + status + "; no report available"));
	}

	/**
	 * The exact design a run simulates, which can be imported again.
	 * @param run the run
	 * @return the design, as stored
	 */
	JsonText input(StoredRun run) {
		return new JsonText(this.repository.input(run.id()));
	}

	/**
	 * The design a run simulates, read, to simulate it again.
	 * @param run the run
	 * @return the design
	 */
	JsonNode inputDocument(StoredRun run) {
		return this.repository.inputDocument(run.id());
	}

	/**
	 * A run's report, read, to compare it.
	 * @param run the run
	 * @return the report, if the run has one
	 */
	Optional<JsonNode> reportDocument(StoredRun run) {
		return this.repository.reportDocument(run.id());
	}

}
