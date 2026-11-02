package io.archsim.parity;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the repository's shared files are, for tests: the examples and the golden files. The build
 * passes the root as the system property {@code archsim.repoRoot}; without it, the folder that
 * holds {@code parity/golden/manifest.json} above the working directory is the root.
 */
public final class Repository {

    private Repository() {}

    public static Path root() {
        String property = System.getProperty("archsim.repoRoot");
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("parity/golden/manifest.json"))) {
                return dir;
            }
        }
        throw new IllegalStateException("cannot find the repository root: set -Darchsim.repoRoot");
    }

    /** The example designs, {@code examples/*.json}: the templates. */
    public static Path examples() {
        return root().resolve("examples");
    }

    /** The golden files of the frozen Python engine. */
    public static Path golden() {
        return root().resolve("parity/golden");
    }
}
