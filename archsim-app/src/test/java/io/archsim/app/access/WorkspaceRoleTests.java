package io.archsim.app.access;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Workspace roles, each allowing what the one before it does. */
class WorkspaceRoleTests {

	@Test
	void atLeast_strongerRole_isTrue() {
		assertThat(WorkspaceRole.OWNER.atLeast(WorkspaceRole.EDITOR)).isTrue();
	}

	@Test
	void atLeast_weakerRole_isFalse() {
		assertThat(WorkspaceRole.VIEWER.atLeast(WorkspaceRole.EDITOR)).isFalse();
	}

	@Test
	void of_storedName_isTheRole() {
		assertThat(WorkspaceRole.of("editor")).isEqualTo(WorkspaceRole.EDITOR);
	}

	@Test
	void of_unknownName_failsWithIllegalArgument() {
		assertThatIllegalArgumentException().isThrownBy(() -> WorkspaceRole.of("admin"));
	}

}
