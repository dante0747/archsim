package io.archsim.apitests;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server configurations the suite tests. A profile is a set of {@code ARCHSIM_*}
 * settings, the same for every implementation of the API; a server of a profile has its
 * own database. Besides these, every server gets {@code ARCHSIM_DATABASE_URL} and {@code
 * ARCHSIM_EXAMPLES_DIR}, and nothing else from the environment.
 */
public enum Profile {

	/**
	 * No sign-in: everyone is the local owner of the default workspace. Default limits,
	 * and two extra price snapshots of a made-up provider ({@code ARCHSIM_PRICES_DIR}).
	 */
	LOCAL("local", 0),
	/**
	 * GitHub and Google sign-in, as a hosted server runs: sessions, CSRF, tokens, rate
	 * limits. GitHub is the suite's {@link FakeGitHub}; Google is configured but never
	 * reached.
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
	 * ({@code
	 * ARCHSIM_OUTBOUND=allowlist}): only to localhost:9090, and only if that name
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

	private final String id;

	private final int portOffset;

	Profile(String id, int portOffset) {
		this.id = id;
		this.portOffset = portOffset;
	}

	/** The profile's name in property names and logs, e.g. {@code small-runs}. */
	public String id() {
		return id;
	}

	/** A started server of this profile listens on {@code archsim.api.port} plus this. */
	public int portOffset() {
		return portOffset;
	}

	/**
	 * The {@code ARCHSIM_*} settings of this profile, beyond the database and examples.
	 */
	public Map<String, String> settings(SuiteConfig config) {
		Map<String, String> env = new LinkedHashMap<>();
		switch (this) {
			case LOCAL ->
				// two snapshots of a made-up provider besides the shipped ones: the
				// engine's
				// fixture (2026-10-01) and a newer copy of it (2026-11-01)
				env.put("ARCHSIM_PRICES_DIR",
						String.join(File.pathSeparator,
								config.repoRoot().resolve("packages/engine/tests/fixtures/pricing").toString(),
								config.repoRoot().resolve("archsim-api-tests/fixtures/prices").toString()));
			case HOSTED -> {
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
			}
			case CONSTRAINED -> {
				env.put("ARCHSIM_MAX_CONCURRENT_RUNS", "1");
				env.put("ARCHSIM_MAX_BODY_BYTES", "10000");
			}
			case SMALL_RUNS -> {
				env.put("ARCHSIM_MAX_REQUESTS", "1000");
				env.put("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS", "prom.example:9090");
			}
			case RATE_LIMITED -> env.put("ARCHSIM_RATE_LIMITS", "true");
			case ALLOWLIST -> {
				env.put("ARCHSIM_OUTBOUND", "allowlist");
				env.put("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS", "localhost:9090");
			}
		}
		return env;
	}

}
