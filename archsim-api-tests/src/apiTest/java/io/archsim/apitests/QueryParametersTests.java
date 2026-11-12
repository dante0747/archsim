package io.archsim.apitests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the API reads a query parameter given once: whole, a comma included, as FastAPI
 * read a query value.
 */
@ApiTest(Profile.LOCAL)
class QueryParametersTests {

	@Test
	void importProject_nameWithAComma_keepsTheWholeName(ApiClient client) {
		ApiResponse imported = client.post(ApiClient.query("/projects/import", "name", "Acme, Inc"),
				Examples.starter());

		assertThat(imported.status()).as(imported.text()).isEqualTo(201);
		assertThat(imported.json().path("name").asString()).isEqualTo("Acme, Inc");
	}

	/**
	 * Without sign-in, the sign-in path checks {@code next} before it says there is none.
	 */
	@Test
	void login_nextOverFiveHundredCharactersBeforeAComma_isRefusedAsFastApiRefusesIt(ApiClient client) {
		ApiResponse response = client.get(ApiClient.query("/auth/github/login", "next", "/" + "a".repeat(500) + ",x"));

		assertThat(response.status()).as(response.text()).isEqualTo(422);
		assertThat(response.json().path("detail").path(0).path("loc")).isEqualTo(Json.array("query", "next"));
	}

}
