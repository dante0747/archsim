package io.archsim.app.reviews;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.Change;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.runs.RunFacts;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Text;
import io.archsim.app.store.Times;

/**
 * Sign-offs given and withdrawn, each with its line in the project's history.
 */
@Component
class SignoffRecords {

	private final SignoffRepository signoffs;

	private final AuditLog audit;

	private final Clock clock;

	SignoffRecords(SignoffRepository signoffs, AuditLog audit, Clock clock) {
		this.signoffs = signoffs;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Stores a sign-off on a run, for the design as saved now.
	 * @param project the run's project
	 * @param run the run
	 * @param request the decision and its note
	 * @param principal the reviewer
	 * @return the sign-off
	 */
	StoredSignoff add(ProjectDesign project, RunFacts run, SignoffCreate request, Principal principal) {
		String note = (request.note() != null) ? Text.strip(request.note()) : "";
		StoredSignoff signoff = new StoredSignoff(Ids.newId(), project.workspaceId(), project.id(), run.id(),
				run.label(), project.revision(), run.inputDigest(), run.resultDigest(), principal.userId(),
				request.decision().json(), note.isEmpty() ? null : note, Times.now(this.clock));
		this.signoffs.add(signoff);
		record("signoff.create", signoff, principal);
		return signoff;
	}

	/**
	 * Deletes a sign-off.
	 * @param signoff the sign-off
	 * @param principal its reviewer
	 */
	void withdraw(StoredSignoff signoff, Principal principal) {
		record("signoff.withdraw", signoff, principal);
		this.signoffs.delete(signoff.id());
	}

	private void record(String action, StoredSignoff signoff, Principal principal) {
		Map<String, @Nullable Object> meta = new LinkedHashMap<>();
		meta.put("decision", signoff.decision());
		meta.put("runId", signoff.runId());
		meta.put("revision", signoff.projectRevision());
		meta.put("note", (signoff.note() != null) ? AuditLog.excerpt(signoff.note()) : null);
		this.audit.record(new Change(signoff.workspaceId(), signoff.projectId(), action, "signoff", signoff.id(), meta),
				principal);
	}

}
