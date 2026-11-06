package io.archsim.app.access;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.SameInstant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspaces and memberships, as the repository reads them for access decisions.
 */
class AccessRepositoryTests extends HostedTestSupport {

	@Autowired
	private AccessRepository repository;

	@Test
	void memberships_joinedAtTheSameInstant_lowestIdFirst() {
		String ada = newId();
		person(ada, "Ada");
		List<String> lowestFirst = SameInstant.storeThree((workspaceId) -> joinAtNine(workspaceId, ada));

		List<WorkspaceRef> memberships = this.repository.memberships(ada);

		assertThat(memberships).extracting(WorkspaceRef::id).containsExactlyElementsOf(lowestFirst);
	}

	/**
	 * Stores a new team workspace that someone joined at nine o'clock.
	 * @param workspaceId the workspace's id
	 * @param userId who joined it
	 */
	private void joinAtNine(String workspaceId, String userId) {
		emptyTeam(workspaceId);
		member(workspaceId, userId, "viewer", SameInstant.NINE);
	}

}
