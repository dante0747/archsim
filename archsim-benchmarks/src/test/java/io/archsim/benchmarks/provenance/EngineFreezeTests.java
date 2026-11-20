package io.archsim.benchmarks.provenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Engines;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What freezing the Java engine as it is would record, as {@code engine-id} prints it.
 */
class EngineFreezeTests {

	private static final String SOURCES = "a".repeat(64);

	private static final String COMMIT = "c".repeat(40);

	private static final String HARNESS = "d".repeat(64);

	private static final String NOW = "2026-11-20T08:00:00Z";

	@Test
	void lines_anEngine_namesItsSourcesAndCommit(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.None());

		assertThat(lines).first().isEqualTo("engine 1.5.0: sources " + SOURCES + ", commit " + COMMIT);
	}

	@Test
	void lines_aCommittedEngineNotFrozenYet_sayWhatItsFreezeRecords(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.None());

		assertThat(lines).last()
			.isEqualTo("to freeze it: tag this commit engine-1.5.0, push the tag, and add to version 1.5.0's entry in "
					+ "benchmarks/engines.json: {\"engineSources\":\"" + SOURCES + "\",\"harnessHash\":\"" + HARNESS
					+ "\",\"frozenAt\":\"" + NOW + "\",\"tag\":\"engine-1.5.0\"}");
	}

	// the designs on a host machine (ADR-0095): the freeze names its fit
	@Test
	void lines_onAHostMachine_recordTheFitsDigestInTheEntry(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.Fit("0123456789abcdef"));

		assertThat(lines).last().asString().endsWith(",\"tag\":\"engine-1.5.0\",\"hostFit\":\"0123456789abcdef\"}");
	}

	// a what-if fit, or checks not committed: the fit named would be no one's to give
	// again
	@Test
	void lines_aHostTheRepositoryCannotGiveAgain_sayWhyInsteadOfAnEntry(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.Unrepeatable("BENCH_HOST_FIT puts the designs on a what-if host machine"));

		assertThat(lines).last().isEqualTo("BENCH_HOST_FIT puts the designs on a what-if host machine");
	}

	// the sources named would not be the ones that run; "null": git could not tell
	@Test
	void lines_anEngineWithUncommittedChanges_sayToCommitThemFirst(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "true"), NOW,
				new FreezeHost.None());

		assertThat(lines).last().asString().contains("uncommitted changes").contains("commit them first");
	}

	@Test
	void lines_anEngineGitCannotVouchFor_sayToCommitFirst(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, "[]").lines("1.5.0", provenance(SOURCES, "null"), NOW,
				new FreezeHost.None());

		assertThat(lines).last().asString().contains("commit them first");
	}

	@Test
	void lines_aVersionFrozenWithTheseSources_saySoAndNameItsTag(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, frozen(SOURCES)).lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.None());

		assertThat(lines).last().isEqualTo("version 1.5.0 is frozen with these sources (tag engine-1.5.0)");
	}

	// the engine changed after its version was frozen: the entry is not to be overwritten
	@Test
	void lines_aVersionFrozenWithOtherSources_sayAFreezeIsNeverEdited(@TempDir Path folder) throws IOException {
		List<String> lines = freeze(folder, frozen("b".repeat(64))).lines("1.5.0", provenance(SOURCES, "false"), NOW,
				new FreezeHost.None());

		assertThat(lines).last()
			.asString()
			.startsWith("version 1.5.0 is already frozen with other sources (bbbbbbbbbbbb, tag engine-1.5.0)")
			.contains("a freeze is never edited")
			.contains("give the engine a new version");
	}

	private static EngineFreeze freeze(Path folder, String engines) throws IOException {
		return new EngineFreeze(Engines.read(Files.writeString(folder.resolve("engines.json"), engines)));
	}

	private static String frozen(String sources) {
		return """
				[{"version": "1.5.0", "engine": "java", "engineSources": "%s", "frozenAt": "2026-11-19",
				  "tag": "engine-1.5.0"}]""".formatted(sources);
	}

	private static JsonNode provenance(String sources, String dirty) {
		return JsonText.read("""
				{"engineSources": "%s", "commit": "%s", "harnessHash": "%s", "dirty": %s}""".formatted(sources, COMMIT,
				HARNESS, dirty));
	}

}
