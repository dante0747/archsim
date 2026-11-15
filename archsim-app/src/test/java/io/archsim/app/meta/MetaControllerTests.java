package io.archsim.app.meta;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.archsim.app.security.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@code GET /health} and {@code GET /meta}. */
@WebMvcTest(MetaController.class)
@Import(SecurityConfiguration.class)
class MetaControllerTests {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private MetaService meta;

	@Test
	void health_databaseAnswers_isOk() {
		given(this.meta.health()).willReturn(Health.OK);

		assertThat(this.mvc.get().uri("/api/v1/health")).hasStatusOk()
			.bodyJson()
			.isEqualTo("{\"status\": \"ok\", \"database\": \"ok\"}");
	}

	@Test
	void health_databaseUnreachable_isServiceUnavailableAndDegraded() {
		given(this.meta.health()).willReturn(Health.DEGRADED);

		assertThat(this.mvc.get().uri("/api/v1/health")).hasStatus(503)
			.bodyJson()
			.isEqualTo("{\"status\": \"degraded\", \"database\": \"unavailable\"}");
	}

	@Test
	void meta_always_answersTheVersionsAndLimitsInCamelCase() {
		given(this.meta.meta())
			.willReturn(new Meta("1.0.0", "0.16.0", "1.7", "1.15", "postgresql", new Meta.Limits(2_000_000, 300.0, 3)));

		assertThat(this.mvc.get().uri("/api/v1/meta")).hasStatusOk().bodyJson().isEqualTo("""
				{"version": "1.0.0", "engineVersion": "0.16.0", "schemaVersion": "1.7", "reportVersion": "1.15",
				 "database": "postgresql",
				 "limits": {"maxRequests": 2000000, "maxWallSeconds": 300.0, "maxConcurrentRuns": 3}}""");
	}

}
