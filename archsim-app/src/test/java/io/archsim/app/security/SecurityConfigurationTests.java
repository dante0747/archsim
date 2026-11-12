package io.archsim.app.security;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Security's chain keeps the API's answers: what it does for a site with a login
 * page of its own stays off, and it adds no headers of its own.
 */
@WebMvcTest(controllers = SecurityConfigurationTests.ChangeController.class)
@Import({ SecurityConfiguration.class, SecurityConfigurationTests.ChangeController.class })
class SecurityConfigurationTests {

	private static final String CHANGE = "/api/v1/change";

	@Autowired
	private MockMvcTester mvc;

	@Test
	void get_loginPage_isARouteThatDoesNotExist() {
		assertThat(this.mvc.get().uri("/login")).hasStatus(404)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Not Found\"}");
	}

	@Test
	void post_logoutPage_isARouteThatDoesNotExist() {
		assertThat(this.mvc.post().uri("/logout")).hasStatus(404);
	}

	@Test
	void post_withoutSignIn_reachesTheEndpoint() {
		assertThat(this.mvc.post().uri(CHANGE)).hasStatusOk();
	}

	@Test
	void get_anyRoute_startsNoHttpSession() {
		MvcTestResult result = this.mvc.get().uri(CHANGE).exchange();

		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(result).doesNotContainHeader(HttpHeaders.SET_COOKIE);
	}

	@Test
	void get_parameterNameTheFirewallRefuses_isABadRequestWithTheApisBody() {
		assertThat(this.mvc.get().uri(CHANGE + "/parameters").param("\u0001", "x")).hasStatus(400)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Bad Request\"}");
	}

	@Test
	void get_headerValueTheFirewallRefuses_isABadRequestWithTheApisBody() {
		assertThat(this.mvc.get().uri(CHANGE + "/agent").header(HttpHeaders.USER_AGENT, "\u0085")).hasStatus(400)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Bad Request\"}");
	}

	@Test
	void get_publicRoute_carriesNoOtherSecurityHeaders() {
		assertThat(this.mvc.get().uri(CHANGE)).doesNotContainHeader(HttpHeaders.CACHE_CONTROL)
			.doesNotContainHeader(HttpHeaders.PRAGMA)
			.doesNotContainHeader(HttpHeaders.EXPIRES)
			.doesNotContainHeader("X-XSS-Protection")
			.doesNotContainHeader("Strict-Transport-Security");
	}

	/**
	 * An endpoint that reads and changes, needing nobody, standing in for the API's
	 * public ones.
	 */
	@RestController
	static class ChangeController {

		@GetMapping("/change")
		Map<String, String> read() {
			return Map.of("answer", "read");
		}

		@PostMapping("/change")
		Map<String, String> change() {
			return Map.of("answer", "changed");
		}

		@GetMapping("/change/parameters")
		Map<String, Integer> parameters(@RequestParam MultiValueMap<String, String> parameters) {
			return Map.of("parameters", parameters.size());
		}

		@GetMapping("/change/agent")
		Map<String, String> agent(@RequestHeader(HttpHeaders.USER_AGENT) String agent) {
			return Map.of("agent", agent);
		}

	}

}
