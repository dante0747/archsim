package io.archsim.apitests;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Trying ArchSim without signing in (ADR-0079): where a hosted server offers sandboxes, a
 * browser holding the CSRF token {@code GET /auth/config} gives it starts one with
 * {@code POST /auth/sandbox}, and is signed in as the sandbox's guest for a day. A server
 * without sign-in offers none.
 */
@ApiTest(Profile.HOSTED)
class SandboxTests {

	/** What a sandbox's guest is told when they try what takes an account. */
	private static final String GUESTS_CANNOT = "A sandbox's guest cannot do this: sign in first.";

	@Test
	void config_hosted_givesTheCsrfTokenThatStartsASandbox(ApiClient client) {
		JsonNode config = client.get("/auth/config").json();

		assertThat(config.path("sandboxes").asBoolean()).isTrue();
		assertThat(config.path("csrfToken").asString()).isEqualTo(client.cookie(ApiServer.CSRF_COOKIE).orElseThrow());
	}

	@Test
	void start_fromTheWebApp_signsTheBrowserInAsAGuest(ApiClient client) {
		ApiResponse started = ApiServer.startSandbox(client);

		assertThat(started.status()).isEqualTo(201);
		JsonNode me = client.get("/auth/me").json();
		assertThat(me.path("kind").asString()).isEqualTo("session");
		assertThat(me.path("user").path("guest").asBoolean()).isTrue();
		assertThat(me.path("user").path("email").isNull()).isTrue();
	}

	@Test
	void start_fromTheWebApp_givesTheGuestASandboxOfTheirOwn(ApiServer server) {
		JsonNode workspaces = server.guest().get("/workspaces").json();

		assertThat(Json.items(workspaces)).singleElement()
			.satisfies((sandbox) -> assertThat(
					Json.object("kind", sandbox.path("kind"), "role", sandbox.path("role"), "maxConcurrentRuns",
							sandbox.path("maxConcurrentRuns"), "maxRunsPerHour", sandbox.path("maxRunsPerHour")))
				.isEqualTo(
						Json.object("kind", "sandbox", "role", "owner", "maxConcurrentRuns", 1, "maxRunsPerHour", 20)));
	}

	@Test
	void start_fromTheWebApp_lastsADay(ApiServer server) {
		Instant expiresAt = Instant
			.parse(server.guest().get("/auth/me").json().path("user").path("expiresAt").asString());

		assertThat(expiresAt).isCloseTo(Instant.now().plus(Duration.ofDays(1)), within(1, ChronoUnit.MINUTES));
	}

	@Test
	void start_withoutTheCsrfHeader_isForbidden(ApiClient client) {
		client.get("/auth/config");

		assertThat(client.post("/auth/sandbox").status()).isEqualTo(403);
	}

	@Test
	void start_sixthInAnHourFromOneAddress_isTooManyRequests(ApiServer server) {
		String address = ApiServer.nextAddress();
		for (int i = 0; i < 5; i++) {
			ApiServer.startSandbox(server.client().header("X-ArchSim-Client-IP", address)).expectStatus(201);
		}

		ApiResponse sixth = ApiServer.startSandbox(server.client().header("X-ArchSim-Client-IP", address));

		assertThat(sixth.status()).isEqualTo(429);
		assertThat(sixth.header("Retry-After")).isPresent();
	}

	@Test
	void createToken_guest_isForbidden(ApiServer server) {
		ApiResponse refused = server.guest().post("/tokens", Json.object("name", "CI", "scope", "read"));

		assertThat(List.of(refused.status(), refused.message())).containsExactly(403, GUESTS_CANNOT);
	}

	@Test
	void createWorkspace_guest_isForbidden(ApiServer server) {
		ApiResponse refused = server.guest().post("/workspaces", Json.object("name", "Team"));

		assertThat(List.of(refused.status(), refused.message())).containsExactly(403, GUESTS_CANNOT);
	}

	@Test
	void start_withoutSignInOnTheServer_isNotFound(@On(Profile.LOCAL) ApiClient client) {
		assertThat(client.post("/auth/sandbox").status()).isEqualTo(404);
	}

}
