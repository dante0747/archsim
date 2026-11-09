package io.archsim.engine.parity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java simulator against the frozen engine's simulation baselines (ADR-0052): every
 * template case of {@code parity/simulation/} ({@link Baselines}), with each seed the
 * baselines recorded, simulated in Java as the baselines were (the whole run, the
 * engine's request limit and an hour's wall-clock limit), judged by the port plan's bar
 * ({@link CaseResult}, {@link ParityBar}), the runs that replay Python's exactly counted,
 * and written to {@code simulation-parity.json} ({@link ParityReport}). A run that fails
 * is recorded with its error ({@link RunError}), as the baselines record one, and the
 * others go on. Hours of simulation: {@code ./gradlew :archsim-engine:parity}, not part
 * of {@code check}.
 */
@Tag("parity")
class SimulationParityTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double AN_HOUR_S = 3600.0;

	private static final SimulationLimits LIMITS = new SimulationLimits(SimulationLimits.DEFAULT.maxRequests(),
			AN_HOUR_S);

	private static final String BASE = "base";

	private static final int PROGRESS_EVERY = 50;

	private static final double NANOS_PER_SECOND = 1e9;

	private static final System.Logger LOG = System.getLogger(SimulationParityTests.class.getName());

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> eachTemplateCase_overItsSeeds_meetsThePortsBar() throws IOException {
		List<RecordedCase> recorded = Baselines.read(baselines(), only());
		long started = System.nanoTime();
		List<CaseResult> results = simulate(recorded);
		ParityReport.write(Repository.root().resolve(setting("archsim.parity.out", "archsim-engine/build/parity")),
				results, threads(), (System.nanoTime() - started) / NANOS_PER_SECOND);
		return results.stream()
			.map((result) -> DynamicTest.dynamicTest(result.template() + " " + result.scenario(),
					() -> assertThat(result.missed()).isEmpty()));
	}

	// each case's runs simulated in Java, several at once, and judged against Python's
	private List<CaseResult> simulate(List<RecordedCase> recorded) {
		Map<String, JsonNode> designs = new LinkedHashMap<>();
		recorded.forEach((one) -> designs.computeIfAbsent(one.template(), SimulationParityTests::design));
		int total = recorded.stream().mapToInt((one) -> one.runs().size()).sum();
		AtomicInteger done = new AtomicInteger();
		Runnable finishedOne = () -> progress(done.incrementAndGet(), total);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads())) {
			List<Pending> pending = recorded.stream()
				.map((one) -> new Pending(one,
						submitted(pool, Objects.requireNonNull(designs.get(one.template()), one.template()), one,
								finishedOne)))
				.toList();
			return pending.stream().map((one) -> CaseResult.of(one.recorded(), finished(one.runs()))).toList();
		}
	}

	// a case's runs, each seed's queued
	private List<Future<JsonNode>> submitted(ExecutorService pool, JsonNode design, RecordedCase recorded,
			Runnable finishedOne) {
		return recorded.runs().stream().map((row) -> pool.submit(() -> {
			try {
				return run(design, recorded.scenario(), row.get("seed").longValue());
			}
			finally {
				finishedOne.run();
			}
		})).toList();
	}

	private JsonNode run(JsonNode design, String scenario, long seed) {
		RunRequest request = RunRequest.of(BASE.equals(scenario) ? null : scenario).withSeed(seed).withLimits(LIMITS);
		return RunFigures.of(this.simulator.run(design.deepCopy(), request));
	}

	// each run's figures, or the error it failed with
	private static List<JsonNode> finished(List<Future<JsonNode>> runs) {
		List<JsonNode> figures = new ArrayList<>();
		for (Future<JsonNode> run : runs) {
			try {
				figures.add(run.get());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("the parity run was interrupted", ex);
			}
			catch (ExecutionException ex) {
				figures.add(RunError.of(Objects.requireNonNullElse(ex.getCause(), ex)));
			}
		}
		return figures;
	}

	private static JsonNode design(String template) {
		return JSON.readTree(Repository.examples().resolve(template + ".json").toFile());
	}

	// a relative folder from the repository's root, as the report's
	private static Path baselines() {
		return Repository.root().resolve(setting("archsim.parity.baselines", "parity/simulation"));
	}

	// the templates asked for; none for every template
	private static List<String> only() {
		return Arrays.stream(setting("archsim.parity.only", "").split(",")).filter((name) -> !name.isBlank()).toList();
	}

	private static int threads() {
		return Integer.parseInt(setting("archsim.parity.threads",
				String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))));
	}

	private static void progress(int done, int total) {
		if (done % PROGRESS_EVERY == 0 || done == total) {
			LOG.log(System.Logger.Level.INFO, "parity: {0} of {1} runs simulated", done, total);
		}
	}

	private static String setting(String name, String otherwise) {
		@Nullable String value = System.getProperty(name);
		return (value == null || value.isBlank()) ? otherwise : value;
	}

	/**
	 * A case and its runs, queued.
	 *
	 * @param recorded the case
	 * @param runs its runs, in the baselines' order
	 */
	private record Pending(RecordedCase recorded, List<Future<JsonNode>> runs) {

	}

}
