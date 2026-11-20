package io.archsim.benchmarks.inputs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.machine.HostSwitch;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W27's in-sample what-if: run.py re-simulated the kept runs with {@code BENCH_HOST=on}
 * and the trial's what-if fit ({@code results/dev/0.16.0-w27}). Each run's inputs derived
 * again with the same fit give the host machine run.py gave it, word for word, and the
 * thread speed at the top pass each CPU-time input was taken to (one, which W29 later
 * moved to another replicate, as run.py gives it today). Half a minute: the parity task
 * runs it.
 */
@Tag("parity")
class HostParityTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	private static final Path W27 = RESULTS.resolve("dev/0.16.0-w27");

	private static final ModelInputs WHAT_IF = ModelInputs
		.asRecorded(Simulations.onTheEngine(),
				HostSwitch
					.fit(Map.of("BENCH_HOST", "on", "BENCH_HOST_FIT",
							RESULTS.resolve("trials/2026-11-01-w27/what-if-fit.json").toString()), RESULTS)
					.orElseThrow());

	/** The CPU-time inputs whose how names the thread speed they were taken to. */
	private static final List<String> SCALED = List.of("serviceTimeMs", "apiServiceTimeMs", "cacheServiceTimeMs",
			"dbCpuShare");

	private static final Pattern SPEED = Pattern
		.compile("x \\d+\\.\\d+, the machine's thread speed at the pass \\([^)]*\\)");

	// W29 later anchored the service time's spread at the top pass,
	// which moved cache-aside run 3's median service time from its third replicate to its
	// first: run.py today gives the first's speed too
	private static final Map<Path, Map<String, String>> MEDIAN_MOVED = Map.of(
			Path.of("cache-aside", "2026-10-30T22-22-04Z.json"),
			Map.of("serviceTimeMs", "x 0.9981, the machine's thread speed at the pass (1.05 busy threads: "
					+ "the containers 0.35, k6 0.40, at rest 0.30)"));

	// each check's inputs derived again, once for both tests
	private static final Map<Path, JsonNode> DERIVED = new ConcurrentHashMap<>();

	@ParameterizedTest
	@MethodSource("w27Checks")
	void rederive_theWhatIfFit_givesTheHostRunPyGave(Path checkFile) {
		JsonNode check = ResultFiles.read(checkFile).orElseThrow();

		JsonNode again = DERIVED.computeIfAbsent(checkFile, HostParityTests::derive);

		assertThat(again.get("host")).isEqualTo(check.at("/inputs/host"));
	}

	// from the fit and the median replicate's top pass alone
	@ParameterizedTest
	@MethodSource("w27Checks")
	void rederive_theWhatIfFit_givesTheThreadSpeedRunPyGave(Path checkFile) {
		Map<String, String> expected = new LinkedHashMap<>(
				speeds(ResultFiles.read(checkFile).orElseThrow().get("inputs")));
		expected.putAll(MEDIAN_MOVED.getOrDefault(W27.relativize(checkFile), Map.of()));

		JsonNode again = DERIVED.computeIfAbsent(checkFile, HostParityTests::derive);

		assertThat(speeds(again)).isNotEmpty().isEqualTo(expected);
	}

	static Stream<Path> w27Checks() {
		try (Stream<Path> experiments = Files.list(W27)) {
			return experiments.flatMap(HostParityTests::files).sorted().toList().stream();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Stream<Path> files(Path folder) {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter((file) -> file.toString().endsWith(".json")).toList().stream();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// a check's run's inputs derived again with the what-if fit
	private static JsonNode derive(Path checkFile) {
		JsonNode check = ResultFiles.read(checkFile).orElseThrow();
		Experiment experiment = Experiments.standard().get(check.get("experiment").stringValue());
		return WHAT_IF.rederive(experiment, ModelInputs.withTrialGc(RESULTS, experiment, calibration(check)));
	}

	// each CPU-time input's thread speed at the pass, as its how names it
	private static Map<String, String> speeds(JsonNode inputs) {
		Map<String, String> speeds = new LinkedHashMap<>();
		SCALED.stream().filter(inputs::has).forEach((key) -> {
			Matcher speed = SPEED.matcher(inputs.get(key).get("how").stringValue());
			speeds.put(key, speed.find() ? speed.group() : "");
		});
		return speeds;
	}

	// the kept calibration the check was made from: the latest's or a kept run's
	private static JsonNode calibration(JsonNode check) {
		Path results = RESULTS.resolve(check.get("results").stringValue());
		try (Stream<Path> runs = Files.list(results)) {
			return Stream
				.concat(Stream.of(results), runs.filter((run) -> run.getFileName().toString().startsWith("run-")))
				.map((run) -> ResultFiles.read(run.resolve("calibration.json")))
				.flatMap(Optional::stream)
				.filter((calibration) -> calibration.path("at").equals(check.get("calibratedAt")))
				.findFirst()
				.orElseThrow();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
