package io.archsim.apitests;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The servers of a test run, one per profile, found from the {@link SuiteConfig} or
 * started on first use and stopped when the run ends.
 */
public final class ApiServers implements AutoCloseable {

	/** The one API the suite can start ({@code archsim.api.start}). */
	private static final String PYTHON = "python";

	private final SuiteConfig config;

	private final Map<Profile, ApiServer> servers = new EnumMap<>(Profile.class);

	private final List<ServerProcess> started = new ArrayList<>();

	private @Nullable FakeGitHub github;

	public ApiServers(SuiteConfig config) {
		this.config = config;
	}

	/**
	 * Returns the server of a profile, started now if need be.
	 * @param profile the profile whose server to return
	 * @return the server, or null when the run has none of {@code profile}
	 */
	public synchronized @Nullable ApiServer server(Profile profile) {
		ApiServer known = this.servers.get(profile);
		if (known != null) {
			return known;
		}
		FakeGitHub fakeGitHub = (profile != Profile.HOSTED) ? null : gitHub();
		String api = this.config.start();
		ApiServer server = (api != null) ? startServer(api, profile, fakeGitHub) : givenServer(profile, fakeGitHub);
		if (server != null) {
			this.servers.put(profile, server);
		}
		return server;
	}

	@Override
	public synchronized void close() {
		this.started.forEach(ServerProcess::stop);
		this.started.clear();
		if (this.github != null) {
			this.github.close();
			this.github = null;
		}
	}

	private @Nullable ApiServer givenServer(Profile profile, @Nullable FakeGitHub fakeGitHub) {
		URI url = this.config.urls().get(profile);
		if (url == null) {
			return null;
		}
		String jdbcUrl = this.config.jdbcUrls().get(profile);
		Db db = (jdbcUrl != null) ? database(jdbcUrl) : null;
		return new ApiServer(profile, url, db, fakeGitHub);
	}

	private ApiServer startServer(String api, Profile profile, @Nullable FakeGitHub fakeGitHub) {
		if (!PYTHON.equals(api)) {
			throw new IllegalArgumentException("archsim.api.start=" + api + ": the API to start is python");
		}
		ServerProcess process = PythonApi.start(this.config, profile);
		this.started.add(process);
		return new ApiServer(profile, process.url(), database(process.jdbcUrl()), fakeGitHub);
	}

	private Db database(String jdbcUrl) {
		return new Db(jdbcUrl, this.config.postgresUser(), this.config.postgresPassword());
	}

	private FakeGitHub gitHub() {
		FakeGitHub running = this.github;
		if (running == null) {
			running = FakeGitHub.start(this.config.fakeGitHubPort());
			this.github = running;
		}
		return running;
	}

}
