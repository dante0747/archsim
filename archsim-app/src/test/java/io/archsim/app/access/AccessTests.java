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
 * The workspace a request means when it names none: the asker's personal one, else the
 * default one, else the oldest it belongs to.
 */
class AccessTests {

	private static final Principal ASKER = new Principal("u1", PrincipalKind.SESSION, null);

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

	private void member(WorkspaceRef... workspaces) {
		given(this.repository.memberships("u1")).willReturn(List.of(workspaces));
		for (WorkspaceRef workspace : workspaces) {
			given(this.repository.workspace(workspace.id())).willReturn(Optional.of(workspace));
			given(this.repository.role(workspace.id(), "u1")).willReturn(Optional.of("editor"));
		}
	}

}
