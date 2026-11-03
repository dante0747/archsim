package io.archsim.app.reviews;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.runs.RunFacts;
import io.archsim.app.runs.RunLookup;
import io.archsim.app.web.ApiException;

/**
 * Review sign-offs given: a reviewer approves a finished run of a design or asks for
 * changes, with a note. Viewers sign off too; a read-only API token does not.
 */
@Service
class SignoffChanges {

	private final RunLookup runs;

	private final ProjectService projects;

	private final SignoffRecords records;

	private final RateLimits limits;

	SignoffChanges(RunLookup runs, ProjectService projects, SignoffRecords records, RateLimits limits) {
		this.runs = runs;
		this.projects = projects;
		this.records = records;
		this.limits = limits;
	}

	/**
	 * Signs off on a finished run. The sign-off applies to the design as saved now (its
	 * revision) and to the run's input and result digests.
	 * @param principal the reviewer
	 * @param runId the run
	 * @param request the decision and its note
	 * @return the sign-off, with its project
	 * @throws ApiException {@code 404} for a run the asker may not see, {@code 403} for a
	 * read-only token, {@code 409} for a run that has not succeeded, {@code 429} after
	 * many sign-offs in an hour
	 */
	@Transactional
	ProjectSignoff create(Principal principal, String runId, SignoffCreate request) {
		RunFacts run = this.runs.require(principal, runId, WorkspaceRole.VIEWER);
		principal.requireWritable();
		if (!run.succeeded()) {
			throw ApiException.conflict("Run is " + run.status() + "; sign off on a finished run");
		}
		ProjectDesign project = this.projects.design(principal, run.projectId(), WorkspaceRole.VIEWER);
		this.limits.enforce("user:" + principal.userId() + ":signoffs", RateLimit.SIGNOFFS_PER_USER,
				"You have signed off many times this hour.");
		return new ProjectSignoff(this.records.add(project, run, request, principal), project);
	}

}
