package io.archsim.benchmarks.machine;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This machine's contention checks, as {@code contention} writes them.
 */
class ContentionChecksTests {

	@Test
	void in_theCommittedResults_namesTheLaptopEachTime() {
		assertThat(ContentionChecks.in(RepositoryLayout.current().results())).isNotEmpty()
			.allSatisfy((path) -> assertThat(machineId(path)).isEqualTo("i7-1165g7"));
	}

	@Test
	void in_theCommittedResults_hasProbeRoundsEachTime() {
		assertThat(ContentionChecks.in(RepositoryLayout.current().results()))
			.allSatisfy((path) -> assertThat(ResultFiles.read(path).orElseThrow().get("probeRounds")).isNotEmpty());
	}

	@Test
	void in_aFolderWithoutChecks_isNone(@TempDir Path results) {
		assertThat(ContentionChecks.in(results)).isEmpty();
	}

	// the probe from the latest check with one, the machine at rest and k6 from the
	// latest quiet check
	@Test
	void latestFit_theLatestCheckBusyAtRest_takesAnEarlierQuietChecksServicePart(@TempDir Path results) {
		write(results, KnownMachine.check(2.5, 0.5).put("at", "2026-11-02T03:00:00Z"));
		write(results, KnownMachine.check());

		assertThat(ContentionChecks.latestFit(results).orElseThrow().how())
			.startsWith("contention check 2026-11-02T03:00:00Z (service part 2026-11-01T03:00:00Z): ");
	}

	@Test
	void latestFit_theLatestCheckWithoutAProbe_takesAnEarlierChecksProbe(@TempDir Path results) {
		ObjectNode serviceOnly = KnownMachine.check().put("at", "2026-11-02T03:00:00Z");
		serviceOnly.putArray("probeRounds");
		write(results, serviceOnly);
		write(results, KnownMachine.check());

		assertThat(ContentionChecks.latestFit(results).orElseThrow().how())
			.startsWith("contention check 2026-11-01T03:00:00Z (service part 2026-11-02T03:00:00Z): ");
	}

	// a quiet check fits with its own service part: named once
	@Test
	void latestFit_theLatestCheckQuiet_takesItsOwnServicePart(@TempDir Path results) {
		write(results, KnownMachine.check().put("at", "2026-11-02T03:00:00Z"));
		write(results, KnownMachine.check());

		assertThat(ContentionChecks.latestFit(results).orElseThrow().how())
			.startsWith("contention check 2026-11-02T03:00:00Z: ");
	}

	@Test
	void latestFit_noCheckWithAProbe_isNone(@TempDir Path results) {
		ObjectNode serviceOnly = KnownMachine.check();
		serviceOnly.putArray("probeRounds").addArray();
		write(results, serviceOnly);

		assertThat(ContentionChecks.latestFit(results)).isEmpty();
	}

	@Test
	void in_twoChecks_isOldestFirst(@TempDir Path results) {
		ResultFiles.write(results.resolve("envcheck/2026-11-02T00-00-00Z-contention.json"), KnownMachine.check());
		ResultFiles.write(results.resolve("envcheck/2026-11-01T00-00-00Z-contention.json"), KnownMachine.check());
		ResultFiles.write(results.resolve("envcheck/2026-11-01T12-00-00Z.json"), KnownMachine.check());

		assertThat(ContentionChecks.in(results).stream().map((path) -> path.getFileName().toString()))
			.containsExactly("2026-11-01T00-00-00Z-contention.json", "2026-11-02T00-00-00Z-contention.json");
	}

	// a check where contention writes it, named after its time
	private static void write(Path results, ObjectNode check) {
		String name = check.get("at").stringValue().replace(':', '-') + "-contention.json";
		ResultFiles.write(results.resolve("envcheck").resolve(name), check);
	}

	private static String machineId(Path check) {
		return ResultFiles.read(check).orElseThrow().at("/environment/machineId").stringValue();
	}

}
