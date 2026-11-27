package io.archsim.apitests;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server configurations the suite tests. A profile is a set of {@code ARCHSIM_*}
 * settings, the same for every implementation of the API; a server of a profile has its
 * own database. Besides these, every server gets {@code ARCHSIM_DATABASE_URL} and
 * {@code ARCHSIM_EXAMPLES_DIR}, and nothing else from the environment.
 */
public enum Profile {

	/**
	 * No sign-in: everyone is the local owner of the default workspace. Default limits,
	 * and two extra price snapshots of a made-up provider ({@code ARCHSIM_PRICES_DIR}).
	 */
	LOCAL("local", 0),
	/**
	 * GitHub and Google sign-in, as a hosted server runs: sessions, CSRF, tokens, rate
	 * limits, and sandboxes for visitors who do not sign in. GitHub is the suite's
	 * {@link FakeGitHub}; Google is configured but never reached.
	 */
	HOSTED("hosted", 1),
	/** No sign-in; one run at a time, and request bodies up to 10,000 bytes. */
	CONSTRAINED("constrained", 2),
	/**
	 * No sign-in; runs of at most 1,000 requests, and Prometheus only at
	 * prom.example:9090.
	 */
	SMALL_RUNS("small-runs", 3),
	/** No sign-in, with the rate limits a hosted server has. */
	RATE_LIMITED("rate-limited", 4),
	/**
	 * No sign-in; calls to Prometheus as a hosted server with an allowlist makes them
	 * ({@code ARCHSIM_OUTBOUND=allowlist}): only to localhost:9090, and only if that name
	 * resolves to public addresses (it does not).
	 */
	ALLOWLIST("allowlist", 5);

	/** The public address of the hosted profile: sign-in callbacks name it. */
	public static final String HOSTED_PUBLIC_URL = "https://archsim.test";

	/** Signs the hosted server's state cookies; any 32+ characters. */
	public static final String HOSTED_SECRET_KEY = "test-secret-key-0123456789-abcdefghijklmnop";

	/**
	 * The hosted server makes the first sign-in with this verified email an owner of
	 * default.
	 */
	public static final String ADMIN_EMAIL = "admin@example.com";

	/** The setting that names the hosts Prometheus may be called at. */
	private static final String PROMETHEUS_ALLOWED_HOSTS = "ARCHSIM_PROMETHEUS_ALLOWED_HOSTS";

	private final String id;

	private final int portOffset;

	Profile(String id, int portOffset) {
		this.id = id;
		this.portOffset = portOffset;
	}

	/**
	 * Returns the profile's name in property names and logs.
	 * @return the name, e.g. {@code small-runs}
	 */
	public String id() {
		return this.id;
	}

	/**
	 * Returns where a started server of this profile listens, relative to the first.
	 * @return what to add to {@code archsim.api.port}
	 */
	public int portOffset() {
		return this.portOffset;
	}

	/**
	 * Returns the {@code ARCHSIM_*} settings of this profile, beyond the database and
	 * examples.
	 * @param config what the suite was told, for the paths and addresses the settings
	 * name
	 * @return the settings' values by name
	 */
	public Map<String, String> settings(SuiteConfig config) {
		return switch (this) {
			case LOCAL -> localSettings(config);
			case HOSTED -> hostedSettings(config);
			case CONSTRAINED -> settingsOf("ARCHSIM_MAX_CONCURRENT_RUNS", "1", "ARCHSIM_MAX_BODY_BYTES", "10000");
			case SMALL_RUNS ->
				settingsOf("ARCHSIM_MAX_REQUESTS", "1000", PROMETHEUS_ALLOWED_HOSTS, "prom.example:9090");
			case RATE_LIMITED -> settingsOf("ARCHSIM_RATE_LIMITS", "true");
			case ALLOWLIST -> settingsOf("ARCHSIM_OUTBOUND", "allowlist", PROMETHEUS_ALLOWED_HOSTS, "localhost:9090");
		};
	}

	/**
	 * Returns the local profile's settings: two snapshots of a made-up provider besides
	 * the shipped ones, the fixture (2026-10-01) and a newer copy of it (2026-11-01), and
	 * the name compose's web container calls the API by, {@code api}, as one it answers.
	 * @param config what the suite was told, for the repository's root
	 * @return the settings' values by name
	 */
	private static Map<String, String> localSettings(SuiteConfig config) {
		String pricesDir = String.join(File.pathSeparator,
				config.repoRoot().resolve("parity/fixtures/pricing").toString(),
				config.repoRoot().resolve("archsim-api-tests/fixtures/prices").toString());
		return settingsOf("ARCHSIM_PRICES_DIR", pricesDir, "ARCHSIM_ALLOWED_HOSTS", "api");
	}

	private static Map<String, String> hostedSettings(SuiteConfig config) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("ARCHSIM_AUTH_MODE", "oauth");
		env.put("ARCHSIM_SECRET_KEY", HOSTED_SECRET_KEY);
		env.put("ARCHSIM_PUBLIC_URL", HOSTED_PUBLIC_URL);
		env.put("ARCHSIM_GITHUB_CLIENT_ID", FakeGitHub.CLIENT_ID);
		env.put("ARCHSIM_GITHUB_CLIENT_SECRET", FakeGitHub.CLIENT_SECRET);
		env.put("ARCHSIM_GITHUB_URL", config.fakeGitHubUrl().toString());
		env.put("ARCHSIM_GITHUB_API_URL", config.fakeGitHubUrl().toString());
		env.put("ARCHSIM_GOOGLE_CLIENT_ID", FakeGitHub.CLIENT_ID);
		env.put("ARCHSIM_GOOGLE_CLIENT_SECRET", FakeGitHub.CLIENT_SECRET);
		env.put("ARCHSIM_ADMIN_EMAILS", ADMIN_EMAIL);
		env.put("ARCHSIM_SANDBOXES", "true");
		return env;
	}

	/**
	 * Builds settings from names and values.
	 * @param namesAndValues each setting's name followed by its value
	 * @return the settings' values by name, in the order given
	 */
	private static Map<String, String> settingsOf(String... namesAndValues) {
		Map<String, String> env = new LinkedHashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			env.put(namesAndValues[i], namesAndValues[i + 1]);
		}
		return env;
	}

}
