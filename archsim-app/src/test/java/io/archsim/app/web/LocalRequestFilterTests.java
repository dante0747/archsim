package io.archsim.app.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;
import io.archsim.app.security.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a server without sign-in answers (ADR-0107): requests addressed to this machine or
 * a name the setting lists, also when the web server passes one on for a browser; changes
 * from its own pages or a listed origin; and bodies in JSON. With sign-in it lets
 * everything through.
 */
@WebMvcTest(controllers = LocalRequestFilterTests.ThingsController.class,
		properties = { "archsim.allowed-hosts=api", "archsim.cors-origins=https://allowed.test" })
@Import({ SecurityConfiguration.class, LocalRequestFilterTests.ThingsController.class })
class LocalRequestFilterTests {

	private static final String THINGS = "/api/v1/things";

	private static final String CLIENT_HOST = "X-ArchSim-Client-Host";

	private static final String CLIENT_ORIGIN = "X-ArchSim-Client-Origin";

	private static final String HOST_REFUSED = """
			{"detail": {"message": "This server answers only requests addressed to this machine: \
			set ARCHSIM_ALLOWED_HOSTS to give it another name", "issues": []}}""";

	private static final String CHANGE_REFUSED = """
			{"detail": {"message": "A change from another site's page is refused: \
			ARCHSIM_CORS_ORIGINS lists the sites that may make one", "issues": []}}""";

	private static final String NOT_JSON = """
			{"detail": {"message": "Send the request body as application/json", "issues": []}}""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvcTester mvc;

	@Test
	void get_addressedToAnotherName_isForbiddenNamingTheSetting() {
		assertThat(this.mvc.get().uri(THINGS).header("Host", "attacker.example")).hasStatus(403)
			.bodyJson()
			.isStrictlyEqualTo(HOST_REFUSED);
	}

	@Test
	void get_addressedToANameTheSettingLists_isServed() {
		assertThat(this.mvc.get().uri(THINGS).header("Host", "API:8000")).hasStatusOk();
	}

	@Test
	void get_passedOnForAPageAddressedToAnotherName_isForbidden() {
		assertThat(this.mvc.get().uri(THINGS).header(CLIENT_HOST, "attacker.example:3000")).hasStatus(403)
			.bodyJson()
			.isStrictlyEqualTo(HOST_REFUSED);
	}

	@Test
	void get_passedOnForAPageOfThisMachine_isServed() {
		assertThat(this.mvc.get().uri(THINGS).header(CLIENT_HOST, "localhost:3000")).hasStatusOk();
	}

	@Test
	void post_fromAnotherSitesPage_isForbiddenNamingTheSetting() {
		assertThat(this.mvc.post().uri(THINGS).header("Origin", "https://evil.test")).hasStatus(403)
			.bodyJson()
			.isStrictlyEqualTo(CHANGE_REFUSED);
	}

	@Test
	void post_fromAPageWithANullOrigin_isForbidden() {
		assertThat(this.mvc.post().uri(THINGS).header("Origin", "null")).hasStatus(403);
	}

	@Test
	void post_fromThisServersOwnPage_isServed() {
		assertThat(
				this.mvc.post().uri(THINGS).header("Host", "localhost:8000").header("Origin", "http://localhost:8000"))
			.hasStatus(201);
	}

	@Test
	void post_fromAListedOriginInCapitals_isServedAsCorsReadsIt() {
		assertThat(this.mvc.post().uri(THINGS).header("Origin", "HTTPS://ALLOWED.TEST")).hasStatus(201);
	}

	@Test
	void post_passedOnFromAnotherSitesPage_isForbidden() {
		assertThat(this.mvc.post()
			.uri(THINGS)
			.header(CLIENT_HOST, "localhost:3000")
			.header(CLIENT_ORIGIN, "https://evil.test")).hasStatus(403).bodyJson().isStrictlyEqualTo(CHANGE_REFUSED);
	}

	@Test
	void post_passedOnFromAnIpv6LoopbackPage_isServed() {
		assertThat(this.mvc.post()
			.uri(THINGS)
			.header(CLIENT_HOST, "[::1]:3000")
			.header(CLIENT_ORIGIN, "http://[::1]:3000")).hasStatus(201);
	}

	@Test
	void get_passedOnForAMalformedHost_isForbidden() {
		assertThat(this.mvc.get().uri(THINGS).header(CLIENT_HOST, "[::1")).hasStatus(403);
	}

	@Test
	void post_passedOnFromThePageTheBrowserAddressed_isServed() {
		assertThat(this.mvc.post()
			.uri(THINGS)
			.header(CLIENT_HOST, "127.0.0.1:3000")
			.header(CLIENT_ORIGIN, "http://127.0.0.1:3000")).hasStatus(201);
	}

	@Test
	void post_withABodyAndNoContentType_isUnsupportedSayingWhatIsAccepted() {
		assertThat(this.mvc.post().uri(THINGS).content("{}")).hasStatus(415)
			.hasHeader("Accept", "application/json")
			.bodyJson()
			.isStrictlyEqualTo(NOT_JSON);
	}

	@Test
	void post_withATextBody_isUnsupported() {
		assertThat(this.mvc.post().uri(THINGS).contentType("text/plain").content("{}")).hasStatus(415);
	}

	@Test
	void post_withAJsonSuffixUnderText_isUnsupported() {
		assertThat(this.mvc.post().uri(THINGS).contentType("text/plain+json").content("{}")).hasStatus(415);
	}

	@Test
	void post_withJsonAndACharset_isServed() {
		assertThat(this.mvc.post().uri(THINGS).contentType("application/json; charset=utf-8").content("{}"))
			.hasStatus(201);
	}

	@Test
	void post_withAMergePatchJsonBody_isServed() {
		assertThat(this.mvc.post().uri(THINGS).contentType("application/merge-patch+json").content("{}"))
			.hasStatus(201);
	}

	@Test
	void post_withoutABody_isServed() {
		assertThat(this.mvc.post().uri(THINGS)).hasStatus(201);
	}

	@Test
	void doFilter_withEveryOriginListed_stillRefusesAChangeFromAnotherSite() throws ServletException, IOException {
		LocalRequestFilter filter = new LocalRequestFilter(TestProperties.with("cors-origins=*"),
				JsonMapper.builder().build());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", THINGS);
		request.addHeader("Origin", "https://evil.test");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(JSON.readTree(response.getContentAsString())).isEqualTo(JSON.readTree(CHANGE_REFUSED));
	}

	@Test
	void doFilter_withSignIn_letsEveryRequestThrough() throws ServletException, IOException {
		LocalRequestFilter filter = new LocalRequestFilter(TestProperties.with("auth-mode=oauth"),
				JsonMapper.builder().build());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", THINGS);
		request.addHeader("Host", "attacker.example");
		request.addHeader("Origin", "https://evil.test");
		request.setContentType("text/plain");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isSameAs(request);
	}

	@RestController
	static class ThingsController {

		@GetMapping("/things")
		Map<String, Object> list() {
			return Map.of();
		}

		@PostMapping("/things")
		ResponseEntity<Map<String, Object>> add() {
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of());
		}

	}

}
