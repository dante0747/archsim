package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim run --seed}, read as argparse's {@code type=int} read it: any text
 * Python's {@code int()} takes, of any size it converts, the design's own check refusing
 * a seed it cannot use (the recorded runs never gave one Java's {@code long} could not
 * hold).
 */
class RunSeedTests {

	@TempDir
	private Path folder;

	@Test
	void run_seedWithSpacesAndUnderscores_isReadAsPythonsIntReadsIt() throws IOException {
		ArchsimRun run = run(" 1_0 ");

		assertThat(run.exitCode()).isZero();
		assertThat(run.out()).contains("seed=10 ");
	}

	@Test
	void run_seedPastTheLargestLong_isRefusedAsTheDesignRefusesIt() throws IOException {
		ArchsimRun run = run("99999999999999999999");

		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(run.err().strip()).isEqualTo("Cannot simulate: document is invalid");
		assertThat(run.out()).contains("workload.seed: Input should be less than or equal to 4294967295");
	}

	@Test
	void run_seedPythonsIntRefuses_isAUsageError() throws IOException {
		ArchsimRun run = run("1__0");

		assertThat(run.exitCode()).isEqualTo(2);
	}

	private ArchsimRun run(String seed) throws IOException {
		Path design = SmallDesign.write(this.folder, SmallDesign.WORKLOAD);
		return ArchsimRun.of("run", design.toString(), "--seed", seed);
	}

}
