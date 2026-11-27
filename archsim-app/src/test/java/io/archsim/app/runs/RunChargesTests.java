package io.archsim.app.runs;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits.Charge;
import io.archsim.app.support.UsedUpLimits;
import io.archsim.app.workspaces.WorkspaceInfo;
import io.archsim.engine.run.SimulationLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;

/**
 * Runs against the hourly limits: a run is counted only once the engine prepared it and
 * every check passed, so a refused run never uses up one of the hour's. A sandbox's runs
 * also count against their address's hour and all sandboxes' (ADR-0106). The tests of
 * those budgets each ask from an address of their own, since the counters live in the
 * database the tests share.
 */
@TestPropertySource(properties = "archsim.rate-limits=true")
class RunChargesTests extends RunTestSupport {

	/** All sandboxes' hour, one counter. */
	private static final String SANDBOXES = "sandboxes:runs";

	@Autowired
	private RunCharges charges;

	/**
	 * Gives every test all of sandboxes' hour: the tests share its one counter.
	 */
	@BeforeEach
	void unspendSandboxesHour() {
		jdbc().sql("DELETE FROM rate_limits WHERE key = :key").param("key", SANDBOXES).update();
	}

	@Test
	void create_overTheHour_isTooManyRequests() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 1);
		started(start(project, "{}"));

		assertThat(start(project, "{}")).hasStatus(429)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("Lab has started its 1 runs for this hour.");
	}

	@Test
	void create_overTheWorkspacesHour_isNotCountedAgainstThePersonsHour() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 1);
		started(start(project, "{}"));
		int counted = personsRunsCounted();

		assertThat(start(project, "{}")).hasStatus(429);

		assertThat(personsRunsCounted()).isEqualTo(counted);
	}

	@Test
	void create_inASandboxWhoseAddressSpentItsHour_isTooManyRequests() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");
		runsPerHour(project, 45);
		for (int group = 0; group < 8; group++) {
			started(startFrom("203.0.113.1", project, "{\"seeds\": 5}"));
		}

		assertThat(startFrom("203.0.113.1", project, "{\"seeds\": 5}")).hasStatus(429)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("Sandboxes from your address have started 40 runs this hour, the most one address may.");
	}

	@Test
	void create_inASandboxWhenSandboxesSpentTheirHour_isTooManyRequests() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");
		UsedUpLimits.useUp(jdbc(), SANDBOXES, new RateLimit(300, RateLimit.HOUR));

		assertThat(startFrom("203.0.113.2", project, "{}")).hasStatus(429)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("Sandboxes have started 300 runs this hour, the most they may together: "
					+ "sign in to run without waiting.");
	}

	@Test
	void create_overAllSandboxesHour_isNotCountedAgainstThePersonsHour() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");
		UsedUpLimits.useUp(jdbc(), SANDBOXES, new RateLimit(300, RateLimit.HOUR));
		int counted = personsRunsCounted();

		assertThat(startFrom("203.0.113.3", project, "{}")).hasStatus(429);

		assertThat(personsRunsCounted()).isEqualTo(counted);
	}

	@Test
	void create_inATeam_isNotCountedAgainstSandboxesHour() {
		prepares(42, null, true);
		String project = project("team", "editor");

		started(startFrom("203.0.113.4", project, "{}"));

		assertThat(List.of(counted(SANDBOXES), counted("ip:203.0.113.4:sandbox-runs"))).containsOnly(0);
	}

	@Test
	void charges_aRunInASandbox_lockThePersonTheAddressAllSandboxesThenTheWorkspace() {
		WorkspaceInfo sandbox = new WorkspaceInfo("s1", "Sandbox", "sandbox", null, null, Instant.now());

		List<Charge> charged = this.charges.charges(sandbox, Principal.guest("g1", "session", "2001:db8:1:2::7"));

		assertThat(charged).extracting(Charge::key)
			.containsExactly("user:g1:runs", "ip:2001:db8:1:2::/64:sandbox-runs", SANDBOXES, "ws:s1:runs");
	}

	@Test
	void create_runTheEngineRefuses_isNotCountedAgainstTheHour() {
		String project = project("team", "editor");
		runsPerHour(project, 1);
		willThrow(new SimulationLimitException("Expected too many requests")).given(preparation()).prepare(any());
		assertThat(start(project, "{}")).hasStatus(422);
		prepares(42, null, true);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void rerun_runTheEngineRefuses_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 2);
		String run = started(start(project, "{}")).path("id").asString();
		willThrow(new SimulationLimitException("Expected too many requests")).given(preparation()).prepare(any());
		assertThat(post("/api/v1/runs/" + run + "/rerun", "")).hasStatus(422);
		prepares(42, null, true);

		assertThat(post("/api/v1/runs/" + run + "/rerun", "")).hasStatus(202);
	}

	@Test
	void create_seedGroupOverTheSeedCap_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 6);
		assertThat(start(project, "{\"seeds\": 6}")).hasStatus(422);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void create_seedGroupWhoseThirdSeedTheEngineRefuses_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		willThrow(new SimulationLimitException("Expected too many requests")).given(preparation())
			.prepare(argThat((PrepareRequest request) -> Long.valueOf(44).equals(request.seed())));
		String project = project("team", "editor");
		runsPerHour(project, 3);
		assertThat(start(project, "{\"seeds\": 3}")).hasStatus(422);
		prepares(42, null, true);

		assertThat(start(project, "{\"seeds\": 3}")).hasStatus(202);
	}

}
