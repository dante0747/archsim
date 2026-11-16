package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hosted server that does not offer sandboxes, as every one does unless told to
 * ({@code ARCHSIM_SANDBOXES}): its config says so and hands out no CSRF token, and a
 * start is not found.
 */
@TestPropertySource(properties = "archsim.sandboxes=false")
class SandboxesOffTests extends HostedTestSupport {

	@Test
	void config_sandboxesOff_offersNoneAndSetsNoCsrfCookie() {
		MvcTestResult config = this.mvc().get().uri("/api/v1/auth/config").exchange();

		assertThat(config).bodyJson()
			.isStrictlyEqualTo("{\"mode\": \"oauth\", \"providers\": [\"github\"], \"sandboxes\": false, "
					+ "\"csrfToken\": null}");
		assertThat(config.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
	}

	@Test
	void start_sandboxesOff_isNotFound() {
		assertThat(this.mvc().post().uri("/api/v1/auth/sandbox")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sandboxes are not offered here");
	}

}
