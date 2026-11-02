package io.archsim.parity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One golden file of the frozen Python engine (parity/README.md): gzip of canonical JSON whose
 * entries record a function's inputs and output. Inputs may refer to values earlier in the file
 * with {@code {"$ref": "/json/pointer"}}; {@link #entries()} resolves them.
 */
public final class GoldenFile {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private final JsonNode root;

    private GoldenFile(String name, JsonNode root) {
        this.name = name;
        this.root = root;
    }

    /** The golden file {@code name}, such as {@code fixtures/migrations} or {@code templates/queueing-lab}. */
    public static GoldenFile read(String name) {
        Path path = Repository.golden().resolve(name + ".json.gz");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return new GoldenFile(name, JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /** The names of every template file, from the manifest. */
    public static List<String> templates() {
        return names("templates/");
    }

    /** The names of every golden file, from the manifest. */
    public static List<String> all() {
        return names("");
    }

    private static List<String> names(String prefix) {
        JsonNode manifest =
                JSON.readTree(Repository.golden().resolve("manifest.json").toFile());
        List<String> names = new ArrayList<>();
        for (JsonNode file : manifest.get("files")) {
            String path = file.get("path").stringValue();
            if (path.startsWith(prefix)) {
                names.add(path.substring(0, path.length() - ".json.gz".length()));
            }
        }
        return names;
    }

    public String name() {
        return name;
    }

    public JsonNode root() {
        return root;
    }

    /** The value at a JSON Pointer, such as {@code /document}. */
    public JsonNode at(String pointer) {
        JsonNode value = root.at(JsonPointer.compile(pointer));
        if (value.isMissingNode()) {
            throw new IllegalArgumentException(name + " has nothing at " + pointer);
        }
        return value;
    }

    /** Every entry in the file, in the file's (sorted) order, inputs resolved. */
    public List<GoldenEntry> entries() {
        List<GoldenEntry> found = new ArrayList<>();
        collect(root, "", found);
        return found;
    }

    /** The entries that recorded {@code function} ({@code load_document} or its qualified name). */
    public List<GoldenEntry> entries(String function) {
        return entries().stream()
                .filter(e -> e.fn().equals(function) || e.function().equals(function))
                .toList();
    }

    private void collect(JsonNode node, String pointer, List<GoldenEntry> found) {
        if (!node.isObject()) {
            return;
        }
        if (node.has("fn") && node.get("fn").isString() && node.has("input")) {
            found.add(new GoldenEntry(
                    name,
                    pointer,
                    node.get("fn").stringValue(),
                    (ObjectNode) resolve(node.get("input")),
                    node.get("output"),
                    node.get("error")));
            return;
        }
        for (Map.Entry<String, JsonNode> child : node.properties()) {
            collect(child.getValue(), pointer + "/" + escape(child.getKey()), found);
        }
    }

    /** {@code value} with every {@code {"$ref": "/..."}} replaced, recursively, by what it points to. */
    public JsonNode resolve(JsonNode value) {
        if (value.isObject()) {
            JsonNode ref = value.get("$ref");
            if (value.size() == 1
                    && ref != null
                    && ref.isString()
                    && ref.stringValue().startsWith("/")) {
                return resolve(at(ref.stringValue()));
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            value.properties().forEach(e -> copy.set(e.getKey(), resolve(e.getValue())));
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> copy.add(resolve(item)));
            return copy;
        }
        return value;
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    @Override
    public String toString() {
        return name;
    }
}
