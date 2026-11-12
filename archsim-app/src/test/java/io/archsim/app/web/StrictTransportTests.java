package io.archsim.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.archsim.app.security.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An API served over HTTPS ({@code ARCHSIM_PUBLIC_URL} starts with {@code https://})
 * tells browsers to keep using HTTPS.
 */
@WebMvcTest(controllers = WebBehaviourTests.ReadOnlyController.class,
		properties = "archsim.public-url=https://archsim.test")
@Import({ SecurityConfiguration.class, WebBehaviourTests.ReadOnlyController.class })
class StrictTransportTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void anyResponse_servedOverHttps_carriesStrictTransportSecurity() {
		assertThat(this.mvc.get().uri("/api/v1/read-only")).hasHeader("Strict-Transport-Security",
				"max-age=31536000 ; includeSubDomains");
	}

}
