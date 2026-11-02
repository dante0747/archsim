package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;

/** Test inputs from the repository: the example designs and the engine's test fixtures. */
public final class Examples {
    private Examples() {}

    /** The repository root, as Gradle passes it ({@code archsim.repoRoot}). */
    public static Path repoRoot() {
        return Path.of(System.getProperty("archsim.repoRoot", ".")).toAbsolutePath();
    }

    /** A fresh copy of {@code examples/<id>.json}. */
    public static ObjectNode load(String id) {
        return Json.copy(Json.read(repoRoot().resolve("examples").resolve(id + ".json")));
    }

    /** The cache-aside starter, shortened to 8 s with 2 s of warm-up. */
    public static ObjectNode starter() {
        return starter(8, 2);
    }

    /** The cache-aside starter with the given duration and warm-up, in seconds. */
    public static ObjectNode starter(int durationS, int warmupS) {
        ObjectNode doc = load("cache-aside-starter");
        ObjectNode workload = (ObjectNode) doc.get("workload");
        workload.put("durationS", durationS);
        workload.put("warmupS", warmupS);
        return doc;
    }

    /** A file under {@code packages/engine/tests/fixtures}. */
    public static Path engineFixture(String relative) {
        return repoRoot().resolve("packages/engine/tests/fixtures").resolve(relative);
    }

    /** A text file, read as UTF-8. */
    public static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
