package io.archsim.app.analysis;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /validate} and {@code /analyze} need to know who is asking, as in the Python
 * API: signed out, a server with sign-in refuses them.
 */
@WebMvcTest(controllers = AnalysisController.class, properties = "archsim.auth-mode=oauth")
class AnalysisSignInTests {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private AnalysisService analysis;

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "/api/v1/validate", "/api/v1/analyze" })
	void post_hostedWithoutSignIn_isUnauthorized(String path) {
		assertThat(this.mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content("""
				{"document": {}}""")).hasStatus(401)
			.hasHeader("WWW-Authenticate", "Bearer")
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign in to continue");
	}

}
