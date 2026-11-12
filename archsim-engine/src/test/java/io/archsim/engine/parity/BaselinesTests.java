package io.archsim.engine.parity;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The simulation baselines as the export script writes them: a manifest, then a gzipped
 * file of each template's cases.
 */
class BaselinesTests {

	@TempDir
	private Path folder;

	@Test
	void read_aManifestOfTwoTemplates_readsEachCaseInItsOrder() throws IOException {
		writeBaselines();

		List<RecordedCase> cases = Baselines.read(this.folder, List.of());

		assertThat(cases).extracting(RecordedCase::template, RecordedCase::scenario, (one) -> one.runs().size())
			.containsExactly(tuple("serverless-burst", "base", 2), tuple("serverless-burst", "cold-start", 1),
					tuple("queueing-lab", "base", 1));
	}

	@Test
	void read_oneTemplateAskedFor_readsItsCasesAlone() throws IOException {
		writeBaselines();

		List<RecordedCase> cases = Baselines.read(this.folder, List.of("queueing-lab"));

		assertThat(cases).extracting(RecordedCase::template).containsExactly("queueing-lab");
	}

	@Test
	void read_aRunThatFailed_keepsItsError() throws IOException {
		writeBaselines();

		List<RecordedCase> cases = Baselines.read(this.folder, List.of("queueing-lab"));

		assertThat(cases.getFirst().runs().getFirst().path("error").stringValue()).isEqualTo("too many events");
	}

	@Test
	void read_aTemplateNotRecorded_isRefused() throws IOException {
		writeBaselines();

		assertThatIllegalArgumentException().isThrownBy(() -> Baselines.read(this.folder, List.of("queueing-lb")))
			.withMessage("not in the baselines: queueing-lb");
	}

	@Test
	void read_aFolderWithoutBaselines_saysToRestoreThem() {
		assertThatIllegalStateException().isThrownBy(() -> Baselines.read(this.folder, List.of()))
			.withMessageEndingWith(
					": the frozen engine's are in parity/simulation (git checkout HEAD -- parity/simulation)");
	}

	private void writeBaselines() throws IOException {
		Files.writeString(this.folder.resolve("manifest.json"), """
				{"files": [{"path": "serverless-burst.json.gz"}, {"path": "queueing-lab.json.gz"}]}""");
		gzip("serverless-burst.json.gz", """
				{"template": "serverless-burst", "cases": {
				  "base": [{"seed": 42, "eventsProcessed": 10}, {"seed": 43, "eventsProcessed": 11}],
				  "cold-start": [{"seed": 42, "eventsProcessed": 12}]}}""");
		gzip("queueing-lab.json.gz", """
				{"template": "queueing-lab", "cases": {"base": [{"seed": 7, "error": "too many events"}]}}""");
	}

	private void gzip(String name, String json) throws IOException {
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(this.folder.resolve(name)))) {
			out.write(json.getBytes(StandardCharsets.UTF_8));
		}
	}

}
