package io.archsim.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** What a started archsim-app does, from its first argument. */
class RoleTests {

	@Test
	void of_api_servesHttp() {
		Role role = Role.of(new String[] { "api", "--server.port=8101" });

		assertThat(role.webApplicationType()).isEqualTo(WebApplicationType.SERVLET);
		assertThat(role.exitsWhenStarted()).isFalse();
	}

	@Test
	void of_migrate_exitsOnceStarted() {
		Role role = Role.of(new String[] { "migrate" });

		assertThat(role.exitsWhenStarted()).isTrue();
	}

	@Test
	void of_worker_runsUntilStoppedWithoutServingHttp() {
		Role role = Role.of(new String[] { "worker" });

		assertThat(role.webApplicationType()).isEqualTo(WebApplicationType.NONE);
		assertThat(role.exitsWhenStarted()).isFalse();
	}

	@Test
	void of_seed_exitsOnceStartedWithoutServingHttp() {
		Role role = Role.of(new String[] { "seed" });

		assertThat(role.webApplicationType()).isEqualTo(WebApplicationType.NONE);
		assertThat(role.exitsWhenStarted()).isTrue();
	}

	@Test
	void of_noArguments_saysWhichRolesThereAre() {
		assertThatIllegalArgumentException().isThrownBy(() -> Role.of(new String[0]))
			.withMessageContaining("[api, migrate, worker, seed]");
	}

	@Test
	void of_unknownRole_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> Role.of(new String[] { "serve" }))
			.withMessageStartingWith("unknown role 'serve'");
	}

}
