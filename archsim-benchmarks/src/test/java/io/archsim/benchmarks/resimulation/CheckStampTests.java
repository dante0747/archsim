package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stamp of what runs now in a repository, which a development check, a range fit and
 * a run's baselines are written under.
 */
class CheckStampTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	@Test
	void of_aRepositoryALabelAndAClock_keepsTheLabelAndTheClock(@TempDir Path repository) {
		CheckStamp stamp = CheckStamp.of("1.5.0-port", repository, NOON);

		assertThat(stamp.label()).isEqualTo("1.5.0-port");
		assertThat(stamp.clock()).isSameAs(NOON);
	}

	// what a run's predictions record: git's ids are None where the folder is no
	// repository
	@Test
	void of_aRepositoryALabelAndAClock_recordsTheProvenanceOfWhatRunsThere(@TempDir Path repository) {
		CheckStamp stamp = CheckStamp.of("1.5.0-port", repository, NOON);

		assertThat(stamp.provenance().propertyNames()).containsExactly("commit", "engineSources", "javaBuild",
				"harnessHash", "dirty", "machineId");
	}

}
