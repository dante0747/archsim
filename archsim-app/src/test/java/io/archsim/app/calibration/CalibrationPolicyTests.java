package io.archsim.app.calibration;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.CalibrationProperties;
import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.config.TestProperties;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a calibration may do, worked out from the server's settings and the workspace.
 */
class CalibrationPolicyTests {

	private static final ArchsimProperties LOCAL = TestProperties.with();

	private static final ArchsimProperties HOSTED = TestProperties.with("auth-mode=oauth");

	@Test
	void outbound_localServer_isOpen() {
		assertThat(policy(LOCAL, TestProperties.calibration()).outbound("team")).isEqualTo(Outbound.OPEN);
	}

	@Test
	void outbound_sandboxOnLocalServer_isOff() {
		assertThat(policy(LOCAL, TestProperties.calibration()).outbound("sandbox")).isEqualTo(Outbound.OFF);
	}

	@Test
	void outbound_hostedServerWithoutHosts_isOff() {
		assertThat(policy(HOSTED, TestProperties.calibration()).outbound("team")).isEqualTo(Outbound.OFF);
	}

	@Test
	void outbound_hostedServerWithHosts_isAllowlist() {
		assertThat(policy(HOSTED, TestProperties.calibration("prometheus-allowed-hosts=prom:9090")).outbound("team"))
			.isEqualTo(Outbound.ALLOWLIST);
	}

	@Test
	void outbound_allowlistSetWithoutHosts_isOff() {
		assertThat(policy(LOCAL, TestProperties.calibration("outbound=allowlist")).outbound("team"))
			.isEqualTo(Outbound.OFF);
	}

	@Test
	void outbound_offSetOnLocalServer_isOff() {
		assertThat(policy(LOCAL, TestProperties.calibration("outbound=off")).outbound("team")).isEqualTo(Outbound.OFF);
	}

	@Test
	void forKind_teamOnLocalServer_carriesTheSettings() {
		OutboundPolicy policy = policy(LOCAL, TestProperties.calibration("prometheus-allowed-hosts=a,b:9090",
				"prometheus-timeout-s=5", "prometheus-max-bytes=2048"))
			.forKind("team");

		assertThat(policy).isEqualTo(new OutboundPolicy(Outbound.OPEN, List.of("a", "b:9090"), 5.0, 2048));
	}

	@Test
	void replayLimits_calibrationCapBelowTheServers_replaysForTheCap() {
		ArchsimProperties server = TestProperties.with("max-wall-seconds=300", "max-requests=5000");

		RunLimits replay = policy(server, TestProperties.calibration("calibrate-max-wall-seconds=30"))
			.replayLimits("team");

		assertThat(replay).isEqualTo(new RunLimits(5000, 30.0));
	}

	@Test
	void replayLimits_serverLimitBelowTheCalibrationCap_replaysForTheServerLimit() {
		ArchsimProperties server = TestProperties.with("max-wall-seconds=40");

		RunLimits replay = policy(server, TestProperties.calibration("calibrate-max-wall-seconds=60"))
			.replayLimits("team");

		assertThat(replay.maxWallSeconds()).isEqualTo(40.0);
	}

	@Test
	void replayLimits_sandboxOnHostedServer_replaysForAtMostItsKindsSeconds() {
		assertThat(policy(HOSTED, TestProperties.calibration()).replayLimits("sandbox").maxWallSeconds())
			.isEqualTo(20.0);
	}

	private static CalibrationPolicy policy(ArchsimProperties properties, CalibrationProperties calibration) {
		return new CalibrationPolicy(new WorkspaceLimits(properties), properties, calibration);
	}

}
