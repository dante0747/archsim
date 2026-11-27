package io.archsim.app.limits;

import java.util.OptionalLong;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.web.ApiException;

/**
 * Each workspace's stored bytes against its kind's quota (ADR-0109): its projects'
 * designs, the designs its runs simulate, each once, its runs' reports, and its share
 * links' copies, as PostgreSQL stores them. A workspace that stores its quota or more
 * takes nothing new that would store more (a project, a design, a run, a share link)
 * until something is deleted. The check comes before the write, so the write that finds a
 * workspace below its quota may take it past by what it stores; checked in turn, with the
 * workspace locked, the next write sees that and is refused.
 */
@Component
public class WorkspaceStorage {

	private final StorageRepository repository;

	private final WorkspaceLimits limits;

	private final Access access;

	WorkspaceStorage(StorageRepository repository, WorkspaceLimits limits, Access access) {
		this.repository = repository;
		this.limits = limits;
		this.access = access;
	}

	/**
	 * The bytes a workspace stores.
	 * @param workspaceId the workspace
	 * @return the bytes, as PostgreSQL stores them
	 */
	long storedBytes(String workspaceId) {
		return this.repository.storedBytes(workspaceId);
	}

	/**
	 * Refuses a write that would store more in a workspace that stores its quota or more.
	 * It takes no lock itself: under {@link Access#lockWorkspace} the check is exact, and
	 * without that lock writes made at once may all pass it.
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} when it stores its quota or more
	 */
	public void requireRoom(String workspaceId) {
		this.repository.kind(workspaceId).ifPresent((kind) -> requireRoom(workspaceId, kind));
	}

	/**
	 * Refuses a write that would store more in a workspace of a kind known already. It
	 * takes no lock itself: under {@link Access#lockWorkspace} the check is exact, and
	 * without that lock writes made at once may all pass it.
	 * @param workspaceId the workspace
	 * @param kind its kind
	 * @throws ApiException {@code 409} when it stores its quota or more
	 */
	public void requireRoom(String workspaceId, String kind) {
		OptionalLong quota = this.limits.maxStoredBytes(kind);
		if (quota.isPresent() && this.repository.storedBytes(workspaceId) >= quota.getAsLong()) {
			throw ApiException
				.conflict("This workspace stores as much as it may (%d MB): delete runs, projects or share links first."
					.formatted(quota.getAsLong() / WorkspaceLimits.MEGABYTE));
		}
	}

	/**
	 * Refuses a write that would store more in a workspace that stores its quota or more,
	 * with the workspace locked until the transaction ends: writes take turns, so each
	 * sees what the ones before it stored.
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} when it stores its quota or more
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void requireRoomInTurn(String workspaceId) {
		this.access.lockWorkspace(workspaceId).ifPresent((workspace) -> requireRoom(workspace.id(), workspace.kind()));
	}

	/**
	 * Makes a write that stores more in a transaction that first checks the workspace's
	 * quota with the workspace locked until it ends, so the write sees what writes made
	 * at once stored before it. Its callers charge their rate limits before they call it
	 * (ADR-0105).
	 * @param <T> what the write makes
	 * @param workspaceId the workspace
	 * @param write the write, which joins the transaction
	 * @return what the write made
	 * @throws ApiException {@code 409} when the workspace stores its quota or more
	 */
	@Transactional
	public <T> T writeInTurn(String workspaceId, Supplier<T> write) {
		requireRoomInTurn(workspaceId);
		return write.get();
	}

}
