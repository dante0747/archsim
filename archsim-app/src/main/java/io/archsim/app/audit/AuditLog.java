package io.archsim.app.audit;

import java.time.Clock;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.PrincipalKind;
import io.archsim.app.store.Times;

/**
 * The history of changes: who did what to which project, run, comment or membership. An
 * event is written in the transaction of the change it records, so both are stored or
 * neither is. Actions are {@code <target>.<verb>}, such as {@code project.create}.
 */
@Component
public class AuditLog {

	private final AuditRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditLog(AuditRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Records a change to a project itself, with its name.
	 * @param action the change, such as {@code project.update}
	 * @param project the project
	 * @param meta what else to record about it; {@code null} values are left out
	 * @param principal who made the change
	 */
	public void project(String action, ProjectTarget project, Map<String, @Nullable Object> meta, Principal principal) {
		ObjectNode details = this.json.createObjectNode().put("name", project.name());
		details.setAll(details(meta));
		this.repository.insert(new AuditEvent(project.workspaceId(), project.id(), actorOf(principal), action,
				"project", project.id(), details, Times.now(this.clock)));
	}

	/**
	 * Records a change to a run: started, cancelled or deleted.
	 * @param action the change, such as {@code run.create}
	 * @param run the run
	 * @param principal who made the change
	 */
	public void run(String action, RunTarget run, Principal principal) {
		ObjectNode details = this.json.createObjectNode();
		if (run.label() != null) {
			details.put("label", run.label());
		}
		if (run.scenarioName() != null) {
			details.put("scenario", run.scenarioName());
		}
		this.repository.insert(new AuditEvent(run.workspaceId(), run.projectId(), actorOf(principal), action, "run",
				run.id(), details, Times.now(this.clock)));
	}

	/**
	 * Records a change to a workspace, its members or its invitations. The target's kind
	 * is the action's first word, {@code user} for a member.
	 * @param action the change, such as {@code member.role}
	 * @param target the workspace, and the member or invitation if it is about one
	 * @param meta what else to record about it; {@code null} values are left out
	 * @param principal who made the change
	 */
	public void workspace(String action, WorkspaceTarget target, Map<String, @Nullable Object> meta,
			Principal principal) {
		String kind = action.substring(0, action.indexOf('.'));
		String targetId = (target.targetId() != null) ? target.targetId() : target.workspaceId();
		this.repository.insert(new AuditEvent(target.workspaceId(), null, actorOf(principal), action,
				"member".equals(kind) ? "user" : kind, targetId, details(meta), Times.now(this.clock)));
	}

	private ObjectNode details(Map<String, @Nullable Object> meta) {
		ObjectNode details = this.json.createObjectNode();
		meta.forEach((key, value) -> {
			if (value != null) {
				details.set(key, this.json.valueToTree(value));
			}
		});
		return details;
	}

	private static Actor actorOf(Principal principal) {
		String kind = (principal.kind() == PrincipalKind.TOKEN) ? "token" : "user";
		return new Actor(principal.userId(), kind, principal.ip());
	}

}
