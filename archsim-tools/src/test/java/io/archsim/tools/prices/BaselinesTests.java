package io.archsim.tools.prices;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BaselinesTests {

	@Test
	void baselineFor_olderSnapshotsOfTheSameRegion_isTheNewestOfThem(@TempDir Path folder) throws IOException {
		snapshot(folder, "aws", "a", "2026-09-01");
		Path newest = snapshot(folder, "aws", "a", "2026-10-01");
		snapshot(folder, "aws", "b", "2026-10-15");
		snapshot(folder, "azure", "a", "2026-10-20");
		snapshot(folder, "aws", "a", "2026-12-01");
		Files.writeString(folder.resolve("broken.json"), "{", StandardCharsets.UTF_8);
		Path current = snapshot(folder, "aws", "a", "2026-11-01");

		assertThat(Baselines.baselineFor(current)).contains(newest);
	}

	@Test
	void baselineFor_noOlderSnapshotOfTheRegion_isNone(@TempDir Path folder) throws IOException {
		snapshot(folder, "aws", "b", "2026-10-15");
		snapshot(folder, "aws", "a", "2026-12-01");
		Path current = snapshot(folder, "aws", "a", "2026-11-01");

		assertThat(Baselines.baselineFor(current)).isEmpty();
	}

	private static Path snapshot(Path folder, String provider, String region, String date) throws IOException {
		Path file = folder.resolve(provider + "-" + region + "-" + date + ".json");
		Files.writeString(file, """
				{"provider": "%s", "region": "%s", "snapshotDate": "%s", "compute": []}
				""".formatted(provider, region, date), StandardCharsets.UTF_8);
		return file;
	}

}
