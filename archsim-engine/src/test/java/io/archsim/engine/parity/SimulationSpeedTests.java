package io.archsim.engine.parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java simulator's speed on every template as shipped (no scenarios), timed as the
 * since-deleted {@code scripts/perf.py} timed the frozen engine's: several runs (five, or
 * {@code -Parchsim.speed.repeats}), with the same limits, after one run that warms the
 * JVM, written in the same shape ({@link SpeedRow}). Minutes of simulation, on a quiet
 * machine: {@code ./gradlew :archsim-engine:speed}, not part of {@code check}.
 */
@Tag("speed")
class SimulationSpeedTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** {@code scripts/perf.py}'s limits: none a template reaches. */
	private static final SimulationLimits UNLIMITED = new SimulationLimits(50_000_000, 3600.0);

	private static final int REPEATS = 5;

	private static final double NANOS_PER_SECOND = 1e9;

	private static final System.Logger LOG = System.getLogger(SimulationSpeedTests.class.getName());

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void everyTemplate_asShipped_isTimed() throws IOException {
		List<Path> templates = templates();
		ArrayNode rows = JSON.createArrayNode();
		templates.forEach((template) -> rows.add(timed(template)));
		ObjectNode out = JSON.createObjectNode()
			.put("label", setting("archsim.speed.label", "java-port"))
			.put("at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
			.put("java", Runtime.version().toString())
			.put("machine", System.getProperty("os.arch"))
			.put("processors", Runtime.getRuntime().availableProcessors());
		out.set("templates", rows);
		Path file = Repository.root().resolve(setting("archsim.speed.out", "archsim-engine/build/parity/speed.json"));
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n");
	}

	// a template's runs: one to warm up, then the timed ones, each giving the same result
	private ObjectNode timed(Path template) {
		String name = template.getFileName().toString().replace(".json", "");
		ObjectNode design = (ObjectNode) JSON.readTree(template.toFile());
		design.putArray("scenarios");
		JsonNode report = run(design);
		Set<String> digests = new TreeSet<>(Set.of(digest(report)));
		List<Double> walls = new ArrayList<>();
		int repeats = Integer.parseInt(setting("archsim.speed.repeats", String.valueOf(REPEATS)));
		for (int repeat = 0; repeat < repeats; repeat++) {
			long started = System.nanoTime();
			report = run(design);
			walls.add((System.nanoTime() - started) / NANOS_PER_SECOND);
			digests.add(digest(report));
		}
		assertThat(digests).as("%s's runs give one result", name).hasSize(1);
		ObjectNode row = SpeedRow.of(name, design, report, walls);
		LOG.log(System.Logger.Level.INFO, "speed: {0} {1} s", name, row.get("bestWallSeconds"));
		return row;
	}

	private static String digest(JsonNode report) {
		return report.path("provenance").path("resultDigest").stringValue();
	}

	private JsonNode run(JsonNode design) {
		return this.simulator.run(design.deepCopy(), RunRequest.of(null).withLimits(UNLIMITED));
	}

	// every template, in scripts/perf.py's order
	private static List<Path> templates() throws IOException {
		try (Stream<Path> files = Files.list(Repository.examples())) {
			return files.filter((path) -> path.getFileName().toString().endsWith(".json"))
				.sorted((one, other) -> one.getFileName().toString().compareTo(other.getFileName().toString()))
				.toList();
		}
	}

	private static String setting(String name, String otherwise) {
		@Nullable String value = System.getProperty(name);
		return (value == null || value.isBlank()) ? otherwise : value;
	}

}
