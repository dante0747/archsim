package io.archsim.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.validation.DocumentInvalidException;
import io.archsim.parity.Repository;
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

/**
 * Whatever JSON comes in, loading either reads a document or says what is wrong with it: no other
 * exception, and a document read is one that writes and reads back the same.
 */
class LoadingPropertiesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Keys of the document, so random JSON reaches deep into it. */
    private static final List<String> KEYS = List.of(
            "schemaVersion",
            "kind",
            "architecture",
            "nodes",
            "edges",
            "id",
            "type",
            "config",
            "source",
            "target",
            "workload",
            "rps",
            "durationS",
            "warmupS",
            "pattern",
            "faults",
            "points",
            "latencyMs",
            "requestTypes",
            "scenarios",
            "overrides",
            "value",
            "layout",
            "provenance",
            "pricing",
            "families",
            "host",
            "zz");

    @Property(tries = 300)
    void any_json_loads_or_is_refused_with_issues(@ForAll("anyJson") JsonNode json) {
        assertLoadsOrIsRefused(json);
    }

    @Property(tries = 300)
    void an_example_with_one_value_replaced_loads_or_is_refused_with_issues(
            @ForAll("examples") JsonNode example, @ForAll("anyJson") JsonNode replacement, @ForAll long seed) {
        assertLoadsOrIsRefused(replaceOneValue(example, replacement, seed));
    }

    private static void assertLoadsOrIsRefused(JsonNode json) {
        try {
            ArchitectureDocument document = DesignDocuments.load(json);
            assertThat(DesignDocuments.load(DesignDocuments.toJson(document))).isEqualTo(document);
        } catch (DocumentInvalidException refused) {
            assertThat(refused.issues()).isNotEmpty();
        }
    }

    @Provide
    Arbitrary<JsonNode> anyJson() {
        return json(3);
    }

    @Provide
    Arbitrary<JsonNode> examples() throws IOException {
        List<JsonNode> examples = new ArrayList<>();
        try (var files = Files.list(Repository.examples())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                examples.add(JSON.readTree(file.toFile()));
            }
        }
        return Arbitraries.of(examples);
    }

    private static Arbitrary<JsonNode> json(int depth) {
        Arbitrary<JsonNode> scalar = Arbitraries.oneOf(
                Arbitraries.just(NODES.nullNode()),
                Arbitraries.of(true, false).map(NODES::booleanNode),
                Arbitraries.integers().map(i -> (JsonNode) NODES.numberNode(i)),
                Arbitraries.doubles().map(d -> (JsonNode) NODES.numberNode(d)),
                Arbitraries.of("", "1.7", "read", "write", "constant", "trace", "x", "-1", "5")
                        .map(NODES::stringNode));
        if (depth == 0) {
            return scalar;
        }
        Arbitrary<JsonNode> inner = json(depth - 1);
        Arbitrary<JsonNode> array = inner.list().ofMaxSize(3).map(items -> {
            ArrayNode node = NODES.arrayNode();
            items.forEach(node::add);
            return node;
        });
        Arbitrary<JsonNode> object =
                Arbitraries.maps(Arbitraries.of(KEYS), inner).ofMaxSize(4).map(LoadingPropertiesTest::object);
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
        } else if (container instanceof ArrayNode array && !array.isEmpty()) {
            array.set(Math.floorMod(seed / 7, array.size()), replacement);
        }
        return copy;
    }

    private static void collect(JsonNode node, List<JsonNode> containers) {
        if (node.isContainer()) {
            containers.add(node);
            node.values().forEach(child -> collect(child, containers));
        }
    }
}
