package io.archsim.app.shares;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.runs.RunLookup;
import io.archsim.app.runs.RunResults;
import io.archsim.app.store.Text;
import io.archsim.app.web.ApiException;

/**
 * New share links (editors only): a frozen copy of a finished run's results, which does
 * not change when the design does or disappear with the run.
 */
@Service
class ShareLinks {

	/** The longest report version a share keeps. */
	private static final int MAX_REPORT_VERSION = 16;

	private final RunLookup runs;

	private final ProjectService projects;

	private final ShareRecords records;

	private final ShareAdmission admission;

	ShareLinks(RunLookup runs, ProjectService projects, ShareRecords records, ShareAdmission admission) {
		this.runs = runs;
		this.projects = projects;
		this.records = records;
		this.admission = admission;
	}

	/**
	 * A share link to a finished run. It stores a copy of the results, so a workspace
	 * that stores its quota or more makes none (ADR-0109). The checks come first, then
	 * the person's limit, charged before the link's own transaction begins, so the
	 * request holds one connection at a time (ADR-0105), then the link is made in a
	 * transaction that checks the quota again ({@link ShareAdmission}).
	 * @param principal who makes it
	 * @param runId the run
	 * @param request its title and how long it works
	 * @return the share
	 * @throws ApiException {@code 403} for a sandbox's guest (whose results stay
	 * unpublished) or a viewer, {@code 404} for a run the asker may not see, {@code 409}
	 * for a run without a report or a workspace that stores its quota or more,
	 * {@code 429} after many links in a day
	 */
	StoredShare create(Principal principal, String runId, ShareCreate request) {
		principal.requireAccount();
		RunResults run = this.runs.withResults(principal, runId, WorkspaceRole.EDITOR);
		JsonNode report = run.report();
		if (!run.facts().succeeded() || report == null) {
			throw ApiException.conflict("Run is " + run.facts().status() + "; only finished runs can be shared");
		}
		ProjectDesign project = this.projects.design(principal, run.facts().projectId(), WorkspaceRole.VIEWER);
		String projectName = project.name();
		String title = (request.title() != null) ? Text.strip(request.title()) : "";
		NewShare share = new NewShare(project.workspaceId(), run.facts(),
				Text.cut(title.isEmpty() ? projectName : title, ShareCreate.MAX_TITLE),
				SharePayloads.of(projectName, run, report), Text.cut(reportVersion(report), MAX_REPORT_VERSION),
				request.expiresInDays());
		return this.admission.admit(principal, project.workspaceId(), () -> this.records.add(share, principal));
	}

	/**
	 * A report's version, as the engine writes it: a string. A report without one (or
	 * with one of another type, which the engine never writes) is {@code unknown}.
	 * @param report the report
	 * @return its version, or {@code unknown}
	 */
	static String reportVersion(JsonNode report) {
		JsonNode version = report.path("reportVersion");
		return (version.isString() && !version.stringValue().isEmpty()) ? version.stringValue() : "unknown";
	}

}
