package io.archsim.app.reviews;

import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectService;

/**
 * The history: what changed in a project or a workspace, newest first, a page at a time.
 * Anyone in the workspace reads it; only owners see the addresses changes came from.
 */
@Service
class Activity {

	private final ActivityRepository events;

	private final PeopleRepository people;

	private final ProjectService projects;

	private final Access access;

	Activity(ActivityRepository events, PeopleRepository people, ProjectService projects, Access access) {
		this.events = events;
		this.people = people;
		this.projects = projects;
		this.access = access;
	}

	/**
	 * A project's history: edits, runs, comments and sign-offs.
	 * @param principal who is asking
	 * @param projectId the project
	 * @param range which changes
	 * @return the page
	 */
	ActivityPage ofProject(Principal principal, String projectId, HistoryRange range) {
		WorkspaceRole role = this.projects.requireAccess(principal, projectId, WorkspaceRole.VIEWER);
		return page(this.events.ofProject(projectId, range), range.limit(), role);
	}

	/**
	 * A workspace's history, including members, invitations and deleted projects.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @param range which changes
	 * @return the page
	 */
	ActivityPage ofWorkspace(Principal principal, String workspaceId, HistoryRange range) {
		WorkspaceRole role = this.access.workspace(principal, new RequestedWorkspace(workspaceId)).role();
		return page(this.events.ofWorkspace(workspaceId, range), range.limit(), role);
	}

	/**
	 * A page of events read one past its size, so the next page is known to exist.
	 * @param read the events, newest first, one more than the page holds when there are
	 * more
	 * @param limit how many the page holds
	 * @param role the asker's role, which decides whether addresses show
	 * @return the page
	 */
	private ActivityPage page(List<StoredEvent> read, int limit, WorkspaceRole role) {
		boolean more = read.size() > limit;
		List<StoredEvent> shown = more ? read.subList(0, limit) : read;
		People who = this.people.named(shown.stream().map(StoredEvent::actorId).toList());
		boolean owner = role == WorkspaceRole.OWNER;
		List<ActivityItem> items = shown.stream()
			.map((event) -> new ActivityItem(event.id(), event.action(), event.targetKind(), event.targetId(),
					event.projectId(), who.of(event.actorId()), event.actorKind(), event.meta(),
					owner ? event.ip() : null, event.createdAt()))
			.toList();
		return new ActivityPage(items, more ? shown.getLast().id() : null);
	}

}
