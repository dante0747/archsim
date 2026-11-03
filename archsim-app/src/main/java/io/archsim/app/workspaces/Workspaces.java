package io.archsim.app.workspaces;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Text;
import io.archsim.app.store.Times;

/**
 * Workspaces: the ones someone belongs to, and new ones with their owner.
 */
@Service
public class Workspaces {

	/** The kind of workspace each person has, listed first. */
	public static final String PERSONAL = "personal";

	/** The longest name a workspace has. */
	private static final int MAX_NAME = 120;

	private final WorkspaceRepository repository;

	private final WorkspaceLimits limits;

	private final Clock clock;

	Workspaces(WorkspaceRepository repository, WorkspaceLimits limits, Clock clock) {
		this.repository = repository;
		this.limits = limits;
		this.clock = clock;
	}

	/**
	 * The workspaces a principal belongs to: just a token's own when it names one, with
	 * the role lowered to viewer for a read-only token.
	 * @param principal who is asking
	 * @return the workspaces, the personal one first, then by name
	 */
	public List<WorkspaceView> of(Principal principal) {
		return this.repository.memberships(principal.userId())
			.stream()
			.filter((membership) -> principal.sees(membership.id()))
			.map((membership) -> view(membership, principal.roleFor(WorkspaceRole.of(membership.role()))))
			.sorted(Comparator.comparing((WorkspaceView view) -> !PERSONAL.equals(view.kind()))
				.thenComparing((view) -> view.name().toLowerCase(Locale.ROOT))
				.thenComparing(WorkspaceView::id))
			.toList();
	}

	/**
	 * A workspace something of it refers to (a project, a run), which therefore exists.
	 * @param id the workspace's id
	 * @return the workspace
	 * @throws IllegalStateException when there is no such workspace
	 */
	public WorkspaceInfo get(String id) {
		return this.repository.find(id)
			.orElseThrow(() -> new IllegalStateException("no workspace " + id + ", which a row refers to"));
	}

	/**
	 * Makes a workspace in the caller's transaction, with its maker as its owner.
	 * @param name its name, cut to 120 characters
	 * @param kind {@code personal}, {@code team} or {@code sandbox}
	 * @param ownerId who makes it
	 * @return the new workspace's id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String create(String name, String kind, String ownerId) {
		String id = Ids.newId();
		this.repository.create(new NewWorkspace(id, Text.cut(name, MAX_NAME), kind, ownerId, Times.now(this.clock)));
		return id;
	}

	private WorkspaceView view(Membership membership, WorkspaceRole role) {
		return new WorkspaceView(membership.id(), membership.name(), membership.kind(), role.json(),
				nullable(this.limits.maxConcurrentRuns(membership.kind(), membership.maxConcurrentRuns())),
				nullable(this.limits.runsPerHour(membership.kind(), membership.maxRunsPerHour())),
				membership.createdAt());
	}

	private static @Nullable Integer nullable(OptionalInt limit) {
		return limit.isPresent() ? limit.getAsInt() : null;
	}

}
