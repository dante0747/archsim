package io.archsim.benchmarks.resimulation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every kept run checked again, against the check the Python harness wrote with the Java
 * engine before it was 1.0.0 ({@code results/dev/0.16.0-java/}): the same inputs, knee,
 * steps and comparison, but for what names the check. Minutes long: the {@code parity}
 * task runs it.
 */
@Tag("parity")
class KeptRunsChecksTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	private static final Path RECORDED = RESULTS.resolve("dev/0.16.0-java");

	private static final DevCheck CHECKS = new DevCheck(RESULTS, ModelInputs.asRecorded(Simulations.onTheEngine()),
			new CheckStamp("1.0.0", JsonNodeFactory.instance.objectNode(), Clock.systemUTC()));

	@ParameterizedTest(name = "{0}")
	@MethodSource("recorded")
	void of_everyKeptRun_isTheRecordedCheckButForWhatNamesIt(String name) {
		JsonNode recorded = ResultFiles.read(RECORDED.resolve(name)).orElseThrow();
		KeptRun run = new KeptRun(recorded.get("experiment").stringValue(),
				RESULTS.resolve(recorded.get("results").stringValue()));

		ObjectNode check = CHECKS.of(run).orElseThrow();

		assertThat(ResultFiles.text(DevCheckTests.withoutWhatNamesIt(check)))
			.isEqualTo(ResultFiles.text(DevCheckTests.withoutWhatNamesIt(recorded)));
	}

	static Stream<String> recorded() throws IOException {
		try (Stream<Path> files = Files.walk(RECORDED)) {
			return files.filter((file) -> file.toString().endsWith(".json"))
				.map((file) -> RECORDED.relativize(file).toString().replace(File.separatorChar, '/'))
				.sorted()
				.toList()
				.stream();
		}
	}

}
