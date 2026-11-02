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

	private final SuiteConfig config;

	private final Map<Profile, ApiServer> servers = new EnumMap<>(Profile.class);

	private final List<ServerProcess> started = new ArrayList<>();

	private @Nullable FakeGitHub github;

	public ApiServers(SuiteConfig config) {
		this.config = config;
	}

	/** The server of {@code profile}, started now if need be; null when there is none. */
	public synchronized @Nullable ApiServer server(Profile profile) {
		ApiServer known = servers.get(profile);
		if (known != null) {
			return known;
		}
		FakeGitHub gitHub = profile == Profile.HOSTED ? gitHub() : null;
		String start = config.start();
		ApiServer server;
		if (start == null) {
			URI url = config.urls().get(profile);
			if (url == null) {
				return null;
			}
			String jdbc = config.jdbcUrls().get(profile);
			Db db = jdbc == null ? null : new Db(jdbc, config.postgresUser(), config.postgresPassword());
			server = new ApiServer(profile, url, db, gitHub);
		}
		else if (start.equals("python")) {
			ServerProcess process = PythonApi.start(config, profile);
			started.add(process);
			server = new ApiServer(profile, process.url(),
					new Db(process.jdbcUrl(), config.postgresUser(), config.postgresPassword()), gitHub);
		}
		else {
			throw new IllegalArgumentException("archsim.api.start=" + start + ": the API to start is python");
		}
		servers.put(profile, server);
		return server;
	}

	private FakeGitHub gitHub() {
		FakeGitHub running = github;
		if (running == null) {
			running = FakeGitHub.start(config.fakeGitHubPort());
			github = running;
		}
		return running;
	}

	@Override
	public synchronized void close() {
		started.forEach(ServerProcess::stop);
		started.clear();
		if (github != null) {
			github.close();
			github = null;
		}
	}

}
