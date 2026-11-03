package io.archsim.app.access;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The workspace a request means when it names none (the asker's personal one, else the
 * default one, else the oldest it belongs to), and what an API token may do there.
 */
class AccessTests {

	private static final Principal ASKER = Principal.session("u1", "s1", null);

	private final AccessRepository repository = mock();

	private final Access access = new Access(this.repository);

	@Test
	void workspace_noneNamedWithAPersonalOne_isThePersonalOne() {
		member(new WorkspaceRef("team1", "team"), new WorkspaceRef("default", "team"),
				new WorkspaceRef("mine", "personal"));

		assertThat(this.access.workspace(ASKER, new RequestedWorkspace(null)).workspace().id()).isEqualTo("mine");
	}

	@Test
	void workspace_noneNamedWithoutAPersonalOne_isTheDefaultOne() {
		member(new WorkspaceRef("team1", "team"), new WorkspaceRef("default", "team"));

		assertThat(this.access.workspace(ASKER, new RequestedWorkspace(null)).workspace().id()).isEqualTo("default");
	}

	@Test
	void workspace_noneNamedWithOnlyOthers_isTheOldest() {
		member(new WorkspaceRef("team1", "team"), new WorkspaceRef("team2", "team"));

		assertThat(this.access.workspace(ASKER, new RequestedWorkspace(null)).workspace().id()).isEqualTo("team1");
	}

	@Test
	void workspace_askerInNone_isNotFound() {
		given(this.repository.memberships("u1")).willReturn(List.of());

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.access.workspace(ASKER, new RequestedWorkspace(null)))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(ex.getMessage()).isEqualTo("A workspace you belong to was not found");
			});
	}

	@Test
	void workspace_noneNamedWithWorkspaceToken_isTheTokensWorkspace() {
		member(new WorkspaceRef("mine", "personal"), new WorkspaceRef("team1", "team"));
		Principal token = Principal.token("u1", new TokenGrant("team1", false), null);

		assertThat(this.access.workspace(token, new RequestedWorkspace(null)).workspace().id()).isEqualTo("team1");
	}

	@Test
	void workspace_otherThanTheTokensOwn_isNotFound() {
		member(new WorkspaceRef("mine", "personal"), new WorkspaceRef("team1", "team"));
		Principal token = Principal.token("u1", new TokenGrant("team1", false), null);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.access.workspace(token, new RequestedWorkspace("mine")))
			.satisfies((ex) -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void require_editingWithReadToken_isForbiddenAsAViewer() {
		member(new WorkspaceRef("team1", "team"));
		Principal token = Principal.token("u1", new TokenGrant(null, true), null);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.access.require(token, "team1", WorkspaceRole.EDITOR, "Project 'p'"))
			.withMessage("You are a viewer in this workspace; this needs editor access.");
	}

	private void member(WorkspaceRef... workspaces) {
		given(this.repository.memberships("u1")).willReturn(List.of(workspaces));
		for (WorkspaceRef workspace : workspaces) {
			given(this.repository.workspace(workspace.id())).willReturn(Optional.of(workspace));
			given(this.repository.role(workspace.id(), "u1")).willReturn(Optional.of("editor"));
		}
	}

}
