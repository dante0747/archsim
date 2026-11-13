package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.Logs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's cgroup counters: from the host's cgroup folder when it can see it, else
 * through {@code docker exec}.
 */
class CgroupsTests {

	@Test
	void read_aContainerTheHostSees_readsItsFolder(@TempDir Path host) throws IOException {
		Path folder = Files.createDirectories(host.resolve("system.slice/docker-3f2a.scope"));
		Files.writeString(folder.resolve("cpu.stat"), "usage_usec 2500000\n");
		Files.writeString(folder.resolve("memory.stat"), "anon 1048576\n");
		Files.writeString(folder.resolve("memory.current"), "2097152\n");
		FakeDocker docker = FakeDocker.silent();

		CgroupReading reading = cgroups(docker, host).read("3f2a");

		assertThat(reading).isEqualTo(new CgroupReading(7.0, 2_500_000L, 2_097_152L, 1_048_576L));
	}

	@Test
	void read_aContainerTheHostCannotSee_catsItsCountersInside(@TempDir Path host) {
		FakeDocker docker = FakeDocker.silent();

		cgroups(docker, host).read("3f2a");

		assertThat(docker.commands()).containsExactly("docker exec 3f2a cat /sys/fs/cgroup/cpu.stat "
				+ "/sys/fs/cgroup/memory.stat /sys/fs/cgroup/memory.current");
	}

	@Test
	void usageUsec_aRunningService_isItsCpuTime(@TempDir Path host) {
		FakeDocker docker = new FakeDocker((command) -> FakeDocker
			.printing(command.contains(" ps -q ") ? "3f2a\n" : "usage_usec 1500\nuser_usec 1000\n"));

		assertThat(cgroups(docker, host).usageUsec("api")).contains(1500L);
	}

	@Test
	void usageUsec_aServiceNotRunning_isNone(@TempDir Path host) {
		assertThat(cgroups(FakeDocker.silent(), host).usageUsec("api")).isEmpty();
	}

	private static Cgroups cgroups(FakeDocker docker, Path host) {
		return new Cgroups(Compose.of(docker, host.resolve("compose.yaml"), Map.of(), new Logs().log()), host,
				new FakeTicker(7.0));
	}

}
