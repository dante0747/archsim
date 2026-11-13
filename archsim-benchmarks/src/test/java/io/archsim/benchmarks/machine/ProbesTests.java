package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.ProcessResult;
import io.archsim.benchmarks.machine.Probes.Probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The probe containers through Docker, as a test plays it.
 */
class ProbesTests {

	// the probe's units, its lateness, its cost in microseconds a unit, its speed and its
	// busy share
	@Test
	void rates_whatAProbePrinted_isItsCostAndSpeed() {
		assertThat(Probes.rates(JsonText.read("{\"units\": 3, \"cpuMs\": 0.6, \"wallMs\": 1.2}"))).contains(
				JsonText.object("{\"units\": 3, \"lateMs\": null, \"cpuUsPerUnit\": 200.0, \"unitsPerWallMs\": 2.5, "
						+ "\"busy\": 0.5}"));
	}

	@Test
	void rates_noUnits_isNone() {
		assertThat(Probes.rates(JsonText.read("{\"units\": 0, \"cpuMs\": 0.6, \"wallMs\": 1.2}"))).isEmpty();
	}

	// the script reaches node as a variable, its quotes intact
	@Test
	void launch_aProbe_pinsItAndPassesTheScriptInAVariable(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		probes(docker, folder).launch(new Probe("alone-probe", 2, 0.5, 12, 1234.4, false));

		assertThat(docker.commands().getFirst())
			.startsWith("docker run -d --name archsim-bench-probe-alone-probe "
					+ "--cpuset-cpus 2 -e PROBE_S=12 -e DUTY=0.5 -e START_AT=1234 -e PROBE_CODE=")
			.endsWith(" --entrypoint node node:24-alpine -e (0,eval)(atob(process.env.PROBE_CODE))");
	}

	@Test
	void launch_aProbeDockerCannotStart_stops(@TempDir Path folder) {
		FakeDocker docker = new FakeDocker((command) -> new ProcessResult(125, "", "no such image"));

		assertThatIllegalStateException()
			.isThrownBy(() -> probes(docker, folder).launch(new Probe("alone-probe", 2, 1, 12, 0, false)))
			.withMessageContaining("no such image");
	}

	@Test
	void collect_aProbeThatPrintedNoJson_isNoneAndRemoved(@TempDir Path folder) {
		FakeDocker docker = answering((command) -> command.startsWith("docker logs ") ? "Killed\n" : "");

		assertThat(probes(docker, folder).collect("alone-probe")).isEmpty();
		assertThat(docker.commands()).last().isEqualTo("docker rm -f archsim-bench-probe-alone-probe");
	}

	@Test
	void collect_aProbe_isItsLastLine(@TempDir Path folder) {
		FakeDocker docker = answering(
				(command) -> command.startsWith("docker logs ") ? "warming up\n{\"units\": 3}\n" : "");

		assertThat(probes(docker, folder).collect("alone-probe")).contains(JsonText.read("{\"units\": 3}"));
	}

	@Test
	void clear_probesLeftBehind_removesThemAll(@TempDir Path folder) {
		FakeDocker docker = answering((command) -> command.contains(" ps -aq ") ? "abc\ndef\n" : "");

		probes(docker, folder).clear();

		assertThat(docker.commands()).last().isEqualTo("docker rm -f abc def");
	}

	@Test
	void clear_noProbeLeft_removesNothing(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		probes(docker, folder).clear();

		assertThat(docker.commands()).containsExactly("docker ps -aq --filter name=archsim-bench-probe-");
	}

	// the host at 1,000,000 s; the VM's clock half a second ahead
	@Test
	void vmClockOffsetMs_aContainerThatPrintedItsTime_isTheVmsLead(@TempDir Path folder) {
		FakeDocker docker = answering((command) -> "1000000500\n");

		assertThat(probes(docker, folder).vmClockOffsetMs()).isEqualTo(500.0);
	}

	@Test
	void vmClockOffsetMs_aContainerThatPrintedNoTime_isNone(@TempDir Path folder) {
		assertThat(probes(answering((command) -> "Unable to find image\n"), folder).vmClockOffsetMs()).isZero();
	}

	private static FakeDocker answering(Function<String, String> output) {
		return new FakeDocker((command) -> FakeDocker.printing(output.apply(command)));
	}

	private static Probes probes(FakeDocker docker, Path folder) {
		Logs logs = new Logs();
		return new Probes(Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), logs.log()),
				new FakeTicker(1_000_000));
	}

}
