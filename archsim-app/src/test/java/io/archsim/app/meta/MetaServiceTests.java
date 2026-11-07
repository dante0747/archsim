package io.archsim.app.meta;

import org.junit.jupiter.api.Test;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.TestProperties;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * What the server says about itself: its health and the versions and limits it runs with.
 */
class MetaServiceTests {

	private static final ArchsimProperties PROPERTIES = TestProperties.with("max-concurrent-runs=3",
			"max-wall-seconds=120", "max-requests=50000");

	private final DatabaseProbe database = mock();

	private final MetaService service = new MetaService(this.database, PROPERTIES);

	@Test
	void meta_settings_answerTheLimitsTheyGive() {
		Meta meta = this.service.meta();

		assertThat(meta.limits()).isEqualTo(new Meta.Limits(50_000, 120.0, 3));
	}

	@Test
	void meta_always_namesTheVersionsAndDatabase() {
		Meta meta = this.service.meta();

		assertThat(meta).extracting(Meta::engineVersion, Meta::schemaVersion, Meta::reportVersion, Meta::database)
			.containsExactly(EngineVersions.ENGINE, "1.7", EngineVersions.REPORT, "postgresql");
	}

	@Test
	void health_databaseAnswers_isOk() {
		given(this.database.answers()).willReturn(true);

		assertThat(this.service.health()).isEqualTo(Health.OK);
	}

	@Test
	void health_databaseSilent_isDegraded() {
		given(this.database.answers()).willReturn(false);

		assertThat(this.service.health()).isEqualTo(Health.DEGRADED);
	}

}
