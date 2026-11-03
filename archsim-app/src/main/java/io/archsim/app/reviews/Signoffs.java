package io.archsim.app.reviews;

import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.runs.RunFacts;
import io.archsim.app.runs.RunLookup;

/**
 * Sign-offs as the asker sees them: who gave them, whether the design was saved again
 * since ({@code stale}), and whether the asker may withdraw them.
 */
@Service
class Signoffs {

	private final SignoffRepository signoffs;

	private final RunLookup runs;

	private final ProjectService projects;

	private final PeopleRepository people;

	Signoffs(SignoffRepository signoffs, RunLookup runs, ProjectService projects, PeopleRepository people) {
		this.signoffs = signoffs;
		this.runs = runs;
		this.projects = projects;
		this.people = people;
	}

	/**
	 * Sign-offs of one run.
	 * @param principal who is asking
	 * @param runId the run
	 * @return the sign-offs, newest first
	 */
	List<SignoffView> ofRun(Principal principal, String runId) {
		RunFacts run = this.runs.require(principal, runId, WorkspaceRole.VIEWER);
		ProjectDesign project = this.projects.design(principal, run.projectId(), WorkspaceRole.VIEWER);
		return views(this.signoffs.ofRun(runId), project, principal);
	}

	/**
	 * Every sign-off of a project's runs.
	 * @param principal who is asking
	 * @param projectId the project
	 * @return the sign-offs, newest first
	 */
	List<SignoffView> ofProject(Principal principal, String projectId) {
		ProjectDesign project = this.projects.design(principal, projectId, WorkspaceRole.VIEWER);
		return views(this.signoffs.ofProject(projectId), project, principal);
	}

	/**
	 * One sign-off as the asker sees it.
	 * @param signoff the sign-off, with its project
	 * @param principal who is asking
	 * @return the sign-off
	 */
	SignoffView view(ProjectSignoff signoff, Principal principal) {
		return views(List.of(signoff.signoff()), signoff.project(), principal).getFirst();
	}

	private List<SignoffView> views(List<StoredSignoff> stored, ProjectDesign project, Principal principal) {
		People who = this.people.named(stored.stream().map(StoredSignoff::reviewerId).toList());
		return stored.stream()
			.map((signoff) -> new SignoffView(signoff.id(), signoff.projectId(), signoff.runId(), signoff.runLabel(),
					signoff.projectRevision(), project.revision(), project.revision() > signoff.projectRevision(),
					signoff.inputDigest(), signoff.resultDigest(), who.of(signoff.reviewerId()), signoff.decision(),
					signoff.note(), signoff.createdAt(),
					!principal.readOnly() && principal.userId().equals(signoff.reviewerId())))
			.toList();
	}

}
