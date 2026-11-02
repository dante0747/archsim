package io.archsim.apitests;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * What the suite is told about the servers it tests. Gradle passes every {@code -Parchsim.*}
 * project property to the test JVM as a system property of the same name.
 *
 * <ul>
 *   <li>{@code archsim.api.start=python}: start the Python API once per profile the tests use,
 *       each on its own port and PostgreSQL database (created afresh), and stop it afterwards.
 *   <li>Otherwise {@code archsim.api.url.<profile>} is the base URL of a running server of that
 *       profile (for example {@code archsim.api.url.local=http://127.0.0.1:8000}), and {@code
 *       archsim.api.jdbc.<profile>} its database, for the tests that set up or check rows. Tests
 *       of a profile without a URL are skipped.
 * </ul>
 *
 * @param repoRoot the repository, for the examples and the engine's test fixtures
 * @param start which API to start ({@code python}), or null to use the URLs given
 * @param basePort the port of the first started server; the others follow in profile order
 * @param fakeGitHubPort where the stand-in GitHub listens; a hosted server must call it there
 * @param postgresUrl a database to connect to when creating the started servers' databases
 * @param databasePrefix the started servers' databases are named {@code <prefix>_<profile>}
 */
public record SuiteConfig(
        Path repoRoot,
        @Nullable String start,
        int basePort,
        int fakeGitHubPort,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String databasePrefix,
        Map<Profile, URI> urls,
        Map<Profile, String> jdbcUrls) {

    public static SuiteConfig fromSystemProperties() {
        return from(System.getProperties());
    }

    public static SuiteConfig from(Properties p) {
        Path root = Path.of(p.getProperty("archsim.repoRoot", ".")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("examples"))) {
            throw new IllegalStateException(
                    "archsim.repoRoot must point at the repository (no examples/ in " + root + ")");
        }
        Map<Profile, URI> urls = new EnumMap<>(Profile.class);
        Map<Profile, String> jdbcUrls = new EnumMap<>(Profile.class);
        for (Profile profile : Profile.values()) {
            String url = p.getProperty("archsim.api.url." + profile.id());
            if (url != null && !url.isBlank()) {
                urls.put(profile, URI.create(url.strip().replaceAll("/+$", "")));
            }
            String jdbc = p.getProperty("archsim.api.jdbc." + profile.id());
            if (jdbc != null && !jdbc.isBlank()) {
                jdbcUrls.put(profile, jdbc.strip());
            }
        }
        String start = p.getProperty("archsim.api.start");
        return new SuiteConfig(
                root,
                start == null || start.isBlank() ? null : start.strip(),
                Integer.parseInt(p.getProperty("archsim.api.port", "8101")),
                Integer.parseInt(p.getProperty("archsim.api.fakeGitHubPort", "8190")),
                p.getProperty("archsim.api.postgres", "jdbc:postgresql://127.0.0.1:5432/archsim"),
                p.getProperty("archsim.api.postgresUser", "archsim"),
                p.getProperty("archsim.api.postgresPassword", "archsim"),
                p.getProperty("archsim.api.databasePrefix", "archsim_api_tests"),
                urls,
                jdbcUrls);
    }

    /** The stand-in GitHub's address, as a hosted server must be configured to call it. */
    public URI fakeGitHubUrl() {
        return URI.create("http://127.0.0.1:" + fakeGitHubPort);
    }
}
