package io.archsim.benchmarks.range;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fit's work from the kept runs' development checks and the cache: what it fits and
 * checks, and that a fit whose work the cache holds simulates nothing.
 */
class RangeFitTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	private static final String LABEL = "0.16.0-java";

	private static final String TRIAL = "trials/2026-11-02-pg-mixed";

	// the unperturbed search's knee, then one per input of RangeInputsTests.INPUTS
	private static final List<Double> KNEES = List.of(1700.0, 1600.0, 1690.0, 1650.0, 1700.0, 1680.0);

	private static final String MEASURED = "{\"rows\": [], \"measuredKnee\": {\"kneeRps\": 1800.0}}";

	// the 100 rps step missed the objective that the 200 rps step met
	private static final String DISTURBED = """
			{"rows": [{"offeredRps": 100, "measured": {"successRate": 0.5, "p95": null}},
			          {"offeredRps": 200, "measured": {"successRate": 1.0, "p95": 1.0}}],
			 "measuredKnee": {"kneeRps": 1800.0}}""";

	private static final String UNMEASURED = "{\"rows\": [], \"measuredKnee\": {\"kneeRps\": null}}";

	@Test
	void fit_aKeptRunWhoseCheckAndSearchesAreKept_runsNoTask(@TempDir Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, LABEL, LABEL);
		StringWriter log = new StringWriter();

		fit(results, searchesDone(root), log);

		assertThat(log.toString().lines())
			.contains(
					"[12:34:56] range fit, engine 0.16.0-java: 1 calibrations, 1 with engine 0.16.0-java's "
							+ "development checks, 0 to derive (or in the cache)",
					"[12:34:56] range fit: 1 counted runs; 6 knee searches for their sensitivity (6 of 6 tasks "
							+ "already done)")
			.noneMatch((line) -> line.endsWith(" done"));
	}

	@Test
	void fit_aKeptRunWithItsCheck_checksItsRange(@TempDir Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, LABEL, LABEL);

		ObjectNode fit = fit(results, searchesDone(root), new StringWriter());

		assertThat(sources(fit.get("checked"))).containsExactly("pg-mixed");
	}

	@Test
	void fit_aKeptRunWithItsCheck_takesItsSensitivityFromTheCachedSearches(@TempDir Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, LABEL, LABEL);

		ObjectNode fit = fit(results, searchesDone(root), new StringWriter());

		assertThat(fit.at("/checked/0/sensitivity"))
			.isEqualTo(Sensitivity.fromKnees(JsonText.read(RangeInputsTests.INPUTS), KNEES));
	}

	@Test
	void fit_aKeptRunWithoutACheck_takesItsInputsFromTheCache(@TempDir Path root) throws IOException {
		Path results = keptRun(root, DISTURBED);

		ObjectNode fit = fit(results, cache(root, inputsOf("pg-mixed")), new StringWriter());

		assertThat(fit.at("/calibrations/0/scales/dbCpuShare").doubleValue()).isEqualTo(0.9);
	}

	@Test
	void fit_aRunWhoseLadderWasDisturbed_isNotChecked(@TempDir Path root) throws IOException {
		Path results = keptRun(root, DISTURBED);

		ObjectNode fit = fit(results, cache(root, inputsOf("pg-mixed")), new StringWriter());

		assertThat(fit.get("checked")).isEmpty();
	}

	@Test
	void fit_aRunWithoutAMeasuredKnee_isNotChecked(@TempDir Path root) throws IOException {
		Path results = keptRun(root, UNMEASURED);
		check(results, LABEL, LABEL);

		ObjectNode fit = fit(results, searchesDone(root), new StringWriter());

		assertThat(fit.get("checked")).isEmpty();
	}

	@Test
	void fit_aTrial_fitsItsCalibration(@TempDir Path root) throws IOException {
		Path results = withATrial(root);

		ObjectNode fit = fit(results, trialDone(root), new StringWriter());

		assertThat(sources(fit.get("calibrations"))).containsExactly("pg-mixed", TRIAL);
	}

	@Test
	void fit_aTrial_isNotChecked(@TempDir Path root) throws IOException {
		Path results = withATrial(root);

		ObjectNode fit = fit(results, trialDone(root), new StringWriter());

		assertThat(sources(fit.get("checked"))).containsExactly("pg-mixed");
	}

	@Test
	void sourcesOf_aCheckOfTheLabelsEngine_isReused(@TempDir Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, LABEL, LABEL);

		RangeFit.Sources sources = RangeFit.Sources.of(results, LABEL);

		assertThat(sources.checked()).isOne();
	}

	@Test
	void sourcesOf_aCheckOfAnotherEngineUnderTheLabel_isNotReused(@TempDir Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, "1.0.0", LABEL);

		RangeFit.Sources sources = RangeFit.Sources.of(results, "1.0.0");

		assertThat(sources.checked()).isZero();
	}

	private static ObjectNode fit(Path results, Path cache, StringWriter log) {
		CheckStamp stamp = new CheckStamp(LABEL, JsonText.read("{\"commit\": \"abc\"}"), NOON);
		return new RangeFit(results, new ModelInputs(Simulations.onTheEngine()), stamp,
				new HarnessLog(new PrintWriter(log), NOON))
			.fit(results.resolveSibling("knee-range.json"), null, cache);
	}

	// pg-mixed's one kept run, calibrated under protocol v5, and its comparison
	private static Path keptRun(Path root, String comparison) throws IOException {
		Path results = root.resolve("results");
		Path run = Files.createDirectories(results.resolve("pg-mixed"));
		Files.writeString(run.resolve("measured.json"), "{\"steps\": []}");
		Files.writeString(run.resolve("calibration.json"), "{\"protocol\": \"v5\", \"at\": \"2026-11-01T00:00:00Z\"}");
		Files.writeString(run.resolve("environment.json"), "{\"cpu\": \"x\"}");
		Files.writeString(run.resolve("comparison.json"), comparison);
		return results;
	}

	// the kept run with its check, and a calibration-only trial of pg-mixed under its
	// conditions
	private static Path withATrial(Path root) throws IOException {
		Path results = keptRun(root, MEASURED);
		check(results, LABEL, LABEL);
		Path trial = Files.createDirectories(results.resolve(TRIAL));
		Files.writeString(trial.resolve("calibration.json"),
				"{\"experiment\": \"pg-mixed\", \"protocol\": \"v5\", \"at\": \"2026-11-02T00:00:00Z\"}");
		Files.writeString(trial.resolve("environment.json"), "{\"cpu\": \"x\"}");
		return results;
	}

	// a cache that holds the kept run's searches and the trial's inputs
	private static Path trialDone(Path root) {
		ObjectNode cached = (ObjectNode) ResultFiles.read(searchesDone(root)).orElseThrow();
		return cache(root, cached.setAll(inputsOf(TRIAL)));
	}

	// the kept run's development check, under a label, by an engine
	private static void check(Path results, String label, String engine) {
		ObjectNode check = JsonNodeFactory.instance.objectNode().put("engineVersion", engine);
		check.set("inputs", JsonText.read(RangeInputsTests.INPUTS));
		check.putObject("knee").put("kneeRps", KNEES.getFirst());
		ResultFiles.write(results.resolve("dev/" + label + "/pg-mixed/2026-11-01T00-00-00Z.json"), check);
	}

	// a cache that holds each of the kept run's sensitivity searches
	private static Path searchesDone(Path root) {
		ObjectNode searches = JsonNodeFactory.instance.objectNode();
		IntStream.range(0, KNEES.size()).forEach((job) -> searches.put("sens|pg-mixed|" + job, KNEES.get(job)));
		return cache(root, searches);
	}

	private static ObjectNode inputsOf(String source) {
		ObjectNode inputs = JsonNodeFactory.instance.objectNode();
		inputs.set("inputs|" + source, JsonText.read(RangeInputsTests.INPUTS));
		return inputs;
	}

	private static Path cache(Path root, JsonNode results) {
		Path file = root.resolve("cache.json");
		ResultFiles.write(file, results);
		return file;
	}

	private static List<String> sources(JsonNode rows) {
		return rows.valueStream().map((row) -> row.get("source").stringValue()).toList();
	}

}
