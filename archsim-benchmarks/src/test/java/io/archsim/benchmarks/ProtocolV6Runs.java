package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Each experiment's last run under protocol v6 and engine 0.16.0, kept under
 * {@code run-N/} since the second held-out campaign archived it: the data of the tests of
 * what those runs recorded and how the page shows them.
 */
public final class ProtocolV6Runs {

	private static final Map<String, String> FOLDERS = Map.of("pg-mixed", "run-16", "pg-hot-row", "run-14",
			"cache-aside", "run-11", "kafka", "run-11", "java-mixed", "run-3", "go-mixed", "run-3", "python-mixed",
			"run-3", "chain", "run-3", "cache-ttl", "run-3");

	private ProtocolV6Runs() {
	}

	/**
	 * An experiment's last run under protocol v6.
	 * @param experiment the experiment's ID
	 * @return the run's folder
	 */
	public static Path of(String experiment) {
		String folder = Objects.requireNonNull(FOLDERS.get(experiment), experiment);
		return RepositoryLayout.current().results().resolve(experiment).resolve(folder);
	}

}
