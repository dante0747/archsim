package io.archsim.app.web;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.security.SecurityConfiguration;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MinLength;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every endpoint shares, as the Python API does it: the error bodies of routes and
 * methods that don't exist, the security headers, and the request-size limit.
 */
@WebMvcTest(
		controllers = { WebBehaviourTests.ReadOnlyController.class, WebBehaviourTests.EchoController.class,
				WebBehaviourTests.FailingController.class },
		properties = { "archsim.max-body-bytes=10000", "archsim.cors-origins=https://allowed.test" })
@Import({ SecurityConfiguration.class, WebBehaviourTests.ReadOnlyController.class,
		WebBehaviourTests.EchoController.class, WebBehaviourTests.FailingController.class })
class WebBehaviourTests {

	/**
	 * Half of a surrogate pair as a JSON escape, the only way it reaches the API in
	 * UTF-8.
	 */
	private static final String LONE_SURROGATE = "\\u%04x".formatted((int) Character.MIN_HIGH_SURROGATE);

	/** The same escape as Jackson writes it, with its hexadecimal digits in capitals. */
	private static final String LONE_SURROGATE_WRITTEN = "\\u%04X".formatted((int) Character.MIN_HIGH_SURROGATE);

	@Autowired
	private MockMvcTester mvc;

	@Test
	void get_unknownRoute_isNotFoundAsFastApiAnswersIt() {
		assertThat(this.mvc.get().uri("/api/v1/nothing-here")).hasStatus(404)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Not Found\"}");
	}

	@Test
	void delete_routeThatOnlyReads_isMethodNotAllowedAsFastApiAnswersIt() {
		assertThat(this.mvc.delete().uri("/api/v1/read-only")).hasStatus(405)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Method Not Allowed\"}");
	}

	@Test
	void delete_routeThatOnlyReads_namesTheMethodsItTakes() {
		assertThat(this.mvc.delete().uri("/api/v1/read-only")).hasHeader("Allow", "GET, HEAD");
	}

	@Test
	void get_acceptingOnlyXml_isAnsweredInJsonAsFastApiIgnoresAccept() {
		assertThat(this.mvc.get().uri("/api/v1/read-only").accept(MediaType.APPLICATION_XML)).hasStatusOk()
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
	}

	@Test
	void get_fromAllowedOrigin_letsTheBrowserReadTheAnswer() {
		assertThat(this.mvc.get().uri("/api/v1/read-only").header("Origin", "https://allowed.test")).hasStatusOk()
			.hasHeader("Access-Control-Allow-Origin", "https://allowed.test");
	}

	@Test
	void get_fromOtherOrigin_isServedWithoutCorsHeadersAsStarletteServesIt() {
		assertThat(this.mvc.get().uri("/api/v1/read-only").header("Origin", "https://other.test")).hasStatusOk()
			.doesNotContainHeader("Access-Control-Allow-Origin");
	}

	@Test
	void get_unknownRouteFromOtherOrigin_isNotFoundAsStarletteAnswersIt() {
		assertThat(this.mvc.get().uri("/api/v1/nothing-here").header("Origin", "https://other.test")).hasStatus(404)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": \"Not Found\"}");
	}

	@Test
	void get_endpointThatFails_isAPlainTextInternalServerError() {
		assertThat(this.mvc.get().uri("/api/v1/fails")).hasStatus(500)
			.hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.hasBodyTextEqualTo("Internal Server Error");
	}

	@Test
	void privateRoute_percentEncoded_isNotCachedEither() {
		assertThat(this.mvc.get().uri(URI.create("/api/v1/%74okens"))).hasHeader("Cache-Control", "no-store");
	}

	@Test
	void privateRoute_withPathParameter_isNotCachedEither() {
		assertThat(this.mvc.get().uri(URI.create("/api/v1/tokens;x=1"))).hasHeader("Cache-Control", "no-store");
	}

	@Test
	void preflight_fromAllowedOrigin_isCachedForTenMinutes() {
		assertThat(this.mvc.options()
			.uri("/api/v1/read-only")
			.header("Origin", "https://allowed.test")
			.header("Access-Control-Request-Method", "GET")).hasStatusOk().hasHeader("Access-Control-Max-Age", "600");
	}

	@Test
	void post_apiBodyThatFits_isReadByTheCodec() {
		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "starter", "count": 2}""")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"name\": \"starter\", \"count\": 2}");
	}

	@Test
	void post_apiBodyWithAnUnknownKey_isReadWithoutIt() {
		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "starter", "count": 2, "colour": "blue"}""")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"name\": \"starter\", \"count\": 2}");
	}

	@Test
	void post_apiBodyMissing_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON)).hasStatus(422)
			.bodyJson()
			.isStrictlyEqualTo("""
					{"detail": [{"type": "missing", "loc": ["body"], "msg": "Field required", "input": null}]}""");
	}

	@Test
	void post_apiBodyNotJson_isAJsonDecodeError() {
		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content("{nope"))
			.hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].msg")
			.isEqualTo("JSON decode error");
	}

	@Test
	void post_apiBodyThatDoesNotFit_namesEachErrorWithPydanticsWords() {
		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content("""
				{"count": 0}""")).hasStatus(422).bodyJson().isStrictlyEqualTo("""
				{"detail": [
				  {"type": "missing", "loc": ["body", "name"], "msg": "Field required", "input": {"count": 0}},
				  {"type": "greater_than_equal", "loc": ["body", "count"],
				   "msg": "Input should be greater than or equal to 1", "input": 0}]}""");
	}

	@Test
	void post_apiBodyWithALoneSurrogateInAKey_isAJsonDecodeError() {
		String body = "{\"name\": \"ab\", \"count\": 1, \"" + LONE_SURROGATE + "\": 1}";

		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content(body))
			.hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].type")
			.isEqualTo("json_invalid");
	}

	@Test
	void post_apiBodyWithALoneSurrogateInAValueItChecks_isRefusedWithTheValueWrittenAsAnEscape() {
		String body = "{\"name\": \"" + LONE_SURROGATE + "\", \"count\": 1}";
		String refusal = "{\"detail\":[{\"type\":\"string_unicode\",\"loc\":[\"body\",\"name\"],"
				+ "\"msg\":\"Input should be a valid string, unable to parse raw data as a unicode string\","
				+ "\"input\":\"" + LONE_SURROGATE_WRITTEN + "\"}]}";

		assertThat(this.mvc.post().uri("/api/v1/echo").contentType(MediaType.APPLICATION_JSON).content(body))
			.hasStatus(422)
			.bodyText()
			.isEqualTo(refusal);
	}

	@Test
	void anyResponse_always_carriesTheSecurityHeaders() {
		assertThat(this.mvc.get().uri("/api/v1/read-only")).hasHeader("X-Content-Type-Options", "nosniff")
			.hasHeader("X-Frame-Options", "DENY")
			.hasHeader("Referrer-Policy", "no-referrer")
			.hasHeader("Cross-Origin-Resource-Policy", "same-origin")
			.hasHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
			.hasHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
	}

	@Test
	void privateRoute_always_isNotCached() {
		assertThat(this.mvc.get().uri("/api/v1/tokens")).hasHeader("Cache-Control", "no-store");
	}

	@Test
	void interactiveDocs_always_keepTheBrowsersContentPolicy() {
		assertThat(this.mvc.get().uri("/api/v1/docs")).doesNotContainHeader("Content-Security-Policy");
	}

	@Test
	void post_bodyOverTheLimit_isRefusedWithTheApisErrorBody() {
		assertThat(this.mvc.post()
			.uri("/api/v1/read-only")
			.contentType(MediaType.APPLICATION_JSON)
			.content("x".repeat(10_001))).hasStatus(413)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": {\"message\": \"Request body too large\", \"issues\": []}}");
	}

	@Test
	void post_bodyOverTheLimit_carriesTheSecurityHeaders() {
		assertThat(this.mvc.post()
			.uri("/api/v1/read-only")
			.contentType(MediaType.APPLICATION_JSON)
			.content("x".repeat(10_001))).hasStatus(413)
			.hasHeader("X-Content-Type-Options", "nosniff")
			.hasHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
	}

	@Test
	void post_chunkedBody_needsAContentLength() {
		assertThat(this.mvc.post().uri("/api/v1/read-only").header("Transfer-Encoding", "chunked")).hasStatus(411)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": {\"message\": \"Content-Length is required\", \"issues\": []}}");
	}

	/**
	 * An endpoint that only answers {@code GET}, standing in for the API's.
	 */
	@RestController
	static class ReadOnlyController {

		@GetMapping("/read-only")
		Map<String, String> read() {
			return Map.of("answer", "read");
		}

	}

	/**
	 * An endpoint that reads its body with the model's codec, standing in for the API's.
	 */
	@RestController
	static class EchoController {

		@PostMapping("/echo")
		Echo echo(@ApiBody Echo echo) {
			return echo;
		}

	}

	/**
	 * A request body, which ignores unknown keys as the API's do: a name, which the codec
	 * checks, and a count of at least one.
	 *
	 * @param name what to echo
	 * @param count how many times
	 */
	@IgnoreUnknownKeys
	record Echo(@MinLength(1) String name, @Ge(1) int count) {

	}

	/**
	 * An endpoint that fails, standing in for a bug.
	 */
	@RestController
	static class FailingController {

		@GetMapping("/fails")
		String fail() {
			throw new IllegalStateException("a bug");
		}

	}

}
