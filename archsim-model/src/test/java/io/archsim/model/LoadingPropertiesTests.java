package io.archsim.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.validation.DocumentValidator;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whatever JSON comes in, loading either reads a document or says what is wrong with it:
 * no other exception, and a document read is one that writes and reads back the same,
 * with no number in it that is NaN or an infinity.
 */
class LoadingPropertiesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** Keys of the document, so random JSON reaches deep into it. */
	private static final List<String> KEYS = List.of("schemaVersion", "kind", "architecture", "nodes", "edges", "id",
			"type", "config", "source", "target", "workload", "rps", "durationS", "warmupS", "pattern", "faults",
			"points", "latencyMs", "requestTypes", "scenarios", "overrides", "value", "layout", "provenance", "pricing",
			"families", "host", "zz");

	/**
	 * Numbers no design means, and the largest and smallest a double holds: as floats,
	 * and as the words and digits the codec reads a float from.
	 */
	private static final List<JsonNode> EXTREMES = List.of(NODES.numberNode(Double.NaN),
			NODES.numberNode(Double.POSITIVE_INFINITY), NODES.numberNode(Double.NEGATIVE_INFINITY),
			NODES.numberNode(Double.MAX_VALUE), NODES.numberNode(-Double.MAX_VALUE), NODES.numberNode(Double.MIN_VALUE),
			NODES.stringNode("nan"), NODES.stringNode("inf"), NODES.stringNode("-inf"), NODES.stringNode("Infinity"),
			NODES.stringNode("1e999"), NODES.stringNode("-1e999"), NODES.stringNode("1e308"));

	private static final DocumentValidator VALIDATOR = DocumentValidator.standard();

	@Property(tries = 300)
	void load_anyJson_readsOrIsRefusedWithIssues(@ForAll("anyJson") JsonNode json) {
		assertLoadsOrIsRefused(json);
	}

	@Property(tries = 300)
	void load_exampleWithOneValueReplaced_readsOrIsRefusedWithIssues(@ForAll("examples") JsonNode example,
			@ForAll("anyJson") JsonNode replacement, @ForAll long seed) {
		assertLoadsOrIsRefused(replaceOneValue(example, replacement, seed));
	}

	@Property(tries = 1000)
	void load_exampleWithOneNumberMadeExtreme_readsNoNumberThatIsNotFinite(@ForAll("examples") JsonNode example,
			@ForAll("extremes") JsonNode extreme, @ForAll long seed) {
		assertThat(floatsRead(replaceOneNumber(example, extreme, seed))).allMatch(Double::isFinite);
	}

	private static void assertLoadsOrIsRefused(JsonNode json) {
		try {
			ArchitectureDocument document = DesignDocuments.load(json);
			assertThat(DesignDocuments.load(DesignDocuments.toJson(document))).isEqualTo(document);
		}
		catch (DocumentInvalidException refused) {
			assertThat(refused.issues()).isNotEmpty();
		}
	}

	/**
	 * The floats a design is simulated with: the document's own, its parts' settings as
	 * their types read them, and the arrivals its workload expects. None when the design
	 * is refused.
	 */
	private static List<Double> floatsRead(JsonNode json) {
		List<Double> floats = new ArrayList<>();
		try {
			ArchitectureDocument document = DesignDocuments.load(json);
			collectFloats(DesignDocuments.toJson(document), floats);
			VALIDATOR.validate(document)
				.configs()
				.values()
				.forEach((config) -> collectFloats(RecordCodec.write(config), floats));
			floats.add(document.workload().expectedArrivals());
			floats.add(document.workload().peakRate());
		}
		catch (DocumentInvalidException refused) {
			assertThat(refused.issues()).isNotEmpty();
		}
		return floats;
	}

	private static void collectFloats(JsonNode node, List<Double> floats) {
		if (node.isFloatingPointNumber()) {
			floats.add(node.doubleValue());
		}
		node.values().forEach((child) -> collectFloats(child, floats));
	}

	@Provide
	Arbitrary<JsonNode> extremes() {
		return Arbitraries.of(EXTREMES);
	}

	@Provide
	Arbitrary<JsonNode> anyJson() {
		return json(3);
	}

	@Provide
	Arbitrary<JsonNode> examples() throws IOException {
		List<JsonNode> examples = new ArrayList<>();
		try (var files = Files.list(Repository.examples())) {
			for (Path file : files.filter((path) -> path.toString().endsWith(".json")).toList()) {
				examples.add(JSON.readTree(file.toFile()));
			}
		}
		return Arbitraries.of(examples);
	}

	private static Arbitrary<JsonNode> json(int depth) {
		Arbitrary<JsonNode> scalar = Arbitraries.oneOf(Arbitraries.just(NODES.nullNode()),
				Arbitraries.of(true, false).map(NODES::booleanNode),
				Arbitraries.integers().map((value) -> (JsonNode) NODES.numberNode(value)),
				Arbitraries.doubles().map((value) -> (JsonNode) NODES.numberNode(value)),
				Arbitraries.of("", "1.7", "read", "write", "constant", "trace", "x", "-1", "5").map(NODES::stringNode));
		if (depth == 0) {
			return scalar;
		}
		Arbitrary<JsonNode> inner = json(depth - 1);
		Arbitrary<JsonNode> array = inner.list().ofMaxSize(3).map((items) -> {
			ArrayNode node = NODES.arrayNode();
			items.forEach(node::add);
			return node;
		});
		Arbitrary<JsonNode> object = Arbitraries.maps(Arbitraries.of(KEYS), inner)
			.ofMaxSize(4)
			.map(LoadingPropertiesTests::object);
		return Arbitraries.oneOf(scalar, array, object);
	}

	private static JsonNode object(Map<String, JsonNode> properties) {
		ObjectNode node = NODES.objectNode();
		properties.forEach(node::set);
		return node;
	}

	/** The example with one of its values, picked by {@code seed}, replaced. */
	private static JsonNode replaceOneValue(JsonNode example, JsonNode replacement, long seed) {
		JsonNode copy = example.deepCopy();
		List<JsonNode> containers = new ArrayList<>();
		collect(copy, containers);
		JsonNode container = containers.get(Math.floorMod(seed, containers.size()));
		if (container instanceof ObjectNode object && !object.isEmpty()) {
			List<String> keys = List.copyOf(object.propertyNames());
			object.set(keys.get(Math.floorMod(seed / 7, keys.size())), replacement);
		}
		else if (container instanceof ArrayNode array && !array.isEmpty()) {
			array.set(Math.floorMod(seed / 7, array.size()), replacement);
		}
		return copy;
	}

	/** The example with one of its numbers, picked by {@code seed}, replaced. */
	private static JsonNode replaceOneNumber(JsonNode example, JsonNode replacement, long seed) {
		JsonNode copy = example.deepCopy();
		List<Runnable> replacements = new ArrayList<>();
		collectNumbers(copy, replacement, replacements);
		replacements.get(Math.floorMod(seed, replacements.size())).run();
		return copy;
	}

	// what would replace each number of a tree, where it is
	private static void collectNumbers(JsonNode node, JsonNode replacement, List<Runnable> replacements) {
		if (node instanceof ObjectNode object) {
			object.properties()
				.stream()
				.filter((property) -> property.getValue().isNumber())
				.forEach((property) -> replacements.add(() -> object.set(property.getKey(), replacement)));
		}
		if (node instanceof ArrayNode array) {
			for (int index = 0; index < array.size(); index++) {
				int position = index;
				if (array.get(position).isNumber()) {
					replacements.add(() -> array.set(position, replacement));
				}
			}
		}
		node.values().forEach((child) -> collectNumbers(child, replacement, replacements));
	}

	private static void collect(JsonNode node, List<JsonNode> containers) {
		if (node.isContainer()) {
			containers.add(node);
			node.values().forEach((child) -> collect(child, containers));
		}
	}

}
