package io.archsim.apitests;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without sign-in, whoever reaches the API owns everything in it, so it answers only what
 * this machine's own clients send: a request addressed to another host name (a page that
 * rebinds its name to this machine) is refused, a change from another site's page is
 * refused, and a body must be JSON, which a page cannot send to another site without its
 * leave.
 */
@ApiTest(Profile.LOCAL)
class LocalRequestsTests {

	private static final String HOST = "Host";

	private static final String ORIGIN = "Origin";

	/** The header the web server's proxy names the host a browser addressed in. */
	private static final String CLIENT_HOST = "X-ArchSim-Client-Host";

	@Test
	void request_addressedToAnotherHostName_isRefused(ApiClient client) {
		ApiResponse response = client.get("/projects", Map.of(HOST, "attacker.example"));

		assertThat(response.status()).as(response.text()).isEqualTo(403);
	}

	@Test
	void request_addressedToThisMachineByName_isServed(ApiClient client) {
		ApiResponse response = client.get("/projects", Map.of(HOST, "localhost:" + client.baseUrl().getPort()));

		assertThat(response.status()).as(response.text()).isEqualTo(200);
	}

	@Test
	void request_addressedToANameTheSettingLists_isServed(ApiClient client) {
		ApiResponse response = client.get("/projects", Map.of(HOST, "api:" + client.baseUrl().getPort()));

		assertThat(response.status()).as(response.text()).isEqualTo(200);
	}

	@Test
	void request_passedOnForAPageAddressedToAnotherName_isRefused(ApiClient client) {
		ApiResponse response = client.get("/projects", Map.of(CLIENT_HOST, "attacker.example:3000"));

		assertThat(response.status()).as(response.text()).isEqualTo(403);
	}

	@Test
	void change_fromAnotherSitesPage_isRefused(ApiClient client) {
		ApiResponse response = client.post("/projects", Json.object("name", "Blank"),
				Map.of(ORIGIN, "https://attacker.example"));

		assertThat(response.status()).as(response.text()).isEqualTo(403);
	}

	@Test
	void change_fromTheWebAppsOrigin_isServed(ApiClient client) {
		ApiResponse response = client.post("/projects", Json.object("name", "Blank"),
				Map.of(ORIGIN, "http://localhost:3000"));

		assertThat(response.status()).as(response.text()).isEqualTo(201);
	}

	@Test
	void change_fromTheApisOwnPage_isServed(ApiClient client) {
		String origin = client.baseUrl().getScheme() + "://" + client.baseUrl().getAuthority();

		ApiResponse response = client.post("/projects", Json.object("name", "Blank"), Map.of(ORIGIN, origin));

		assertThat(response.status()).as(response.text()).isEqualTo(201);
	}

	@Test
	void change_fromAPageWithANullOrigin_isRefused(ApiClient client) {
		ApiResponse response = client.post("/projects", Json.object("name", "Blank"), Map.of(ORIGIN, "null"));

		assertThat(response.status()).as(response.text()).isEqualTo(403);
	}

	@Test
	void change_withABodyAndNoContentType_isRefused(ApiClient client) {
		ApiResponse response = client.postWithoutType("/projects", "{\"name\": \"Blank\"}");

		assertThat(response.status()).as(response.text()).isEqualTo(415);
	}

	@Test
	void change_withABodyThatIsNotJson_isRefused(ApiClient client) {
		ApiResponse response = client.post("/validate", Json.object("document", Examples.starter()),
				Map.of("Content-Type", "text/plain"));

		assertThat(response.status()).as(response.text()).isEqualTo(415);
	}

}
