package io.archsim.benchmarks.machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The benchmark designs' host machine is opt-in ({@code BENCH_HOST=on}): from the latest
 * quiet contention check, or from a fit a what-if names, its clock set when one is given.
 */
class HostSwitchTests {

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	@ParameterizedTest
	@ValueSource(strings = { "", "off" })
	void fit_switchedOff_isNone(String value, @TempDir Path folder) throws IOException {
		Path given = fitFile(folder);

		assertThat(HostSwitch.fit(Map.of("BENCH_HOST", value, "BENCH_HOST_FIT", given.toString()), folder)).isEmpty();
	}

	@Test
	void fit_notSet_isNone(@TempDir Path folder) {
		assertThat(HostSwitch.fit(Map.of(), folder)).isEmpty();
	}

	@Test
	void fit_switchedOnWithAFitGiven_isThatFit(@TempDir Path folder) throws IOException {
		Path given = fitFile(folder);

		assertThat(HostSwitch.fit(Map.of("BENCH_HOST", "ON", "BENCH_HOST_FIT", given.toString()), folder))
			.contains(FIT);
	}

	@Test
	void fit_aClockGiven_setsTheAllCoreClock(@TempDir Path folder) throws IOException {
		Map<String, String> environment = Map.of("BENCH_HOST", "on", "BENCH_HOST_FIT", fitFile(folder).toString(),
				"BENCH_HOST_CLOCK", "0.9");

		assertThat(HostSwitch.fit(environment, folder).orElseThrow().allCoreClock()).isEqualTo(0.9);
	}

	@Test
	void fit_aClockGiven_saysItIsAWhatIf(@TempDir Path folder) throws IOException {
		Map<String, String> environment = Map.of("BENCH_HOST", "on", "BENCH_HOST_FIT", fitFile(folder).toString(),
				"BENCH_HOST_CLOCK", "0.9");

		assertThat(HostSwitch.fit(environment, folder).orElseThrow().how()).endsWith("; clock set to 0.9 (what-if)");
	}

	@Test
	void fit_switchedOnWithAQuietCheck_isItsFit(@TempDir Path results) {
		ResultFiles.write(results.resolve("envcheck/2026-11-01T03-00-00Z-contention.json"), KnownMachine.check());

		assertThat(HostSwitch.fit(Map.of("BENCH_HOST", "on"), results)).contains(FIT);
	}

	// the committed check's service part never ran quiet: no machine
	@Test
	void fit_switchedOnWithTheCommittedChecks_isNone() {
		assertThat(HostSwitch.fit(Map.of("BENCH_HOST", "on"), RepositoryLayout.current().results())).isEmpty();
	}

	private static Path fitFile(Path folder) throws IOException {
		Path file = folder.resolve("fit.json");
		Files.createDirectories(folder);
		ResultFiles.write(file, FIT.json());
		return file;
	}

}
