package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool of PostgreSQL connections {@code compose.yaml} gives the services of an
 * experiment's stack ({@code PG_POOL}) is the one its design gives PostgreSQL: the ten
 * every service starts from, unless a service of the stack sets its own. No service
 * reports its pool in {@code /stats}, so a smoke test cannot check it.
 */
class ComposePoolTests {

	private static final Pattern SERVICE = Pattern.compile("^  ([\\w-]+):\\s*$");

	private static final Pattern POOL = Pattern.compile("^\\s+PG_POOL: \"(\\d+)\"\\s*$");

	private static final List<String> LINES = lines();

	/** Where the services begin: the settings they share are above it. */
	private static final int SERVICES = LINES.indexOf("services:");

	// x-service and x-runtime, which every service that calls PostgreSQL starts from
	@Test
	void compose_theSettingsEveryServiceStartsFrom_giveItTenConnections() {
		assertThat(sharedPools()).containsExactly(Experiment.DEFAULT_POOL, Experiment.DEFAULT_POOL);
	}

	@Test
	void compose_goPoolsService_isTheOneThatSetsAPoolOfItsOwn() {
		assertThat(ownPools()).containsExactly(Map.entry("api-go-pool", 2));
	}

	@ParameterizedTest
	@MethodSource("experiments")
	void compose_anExperimentsStack_hasThePoolItsDesignGivesPostgresql(Experiment experiment) {
		assertThat(poolOf(experiment.stack())).isEqualTo(experiment.connections());
	}

	static Stream<Experiment> experiments() {
		return Experiments.standard().all().stream();
	}

	// the pool a service of the stack sets for itself, else the one it starts from
	private static int poolOf(String stack) {
		Map<String, Integer> own = ownPools();
		return Stacks.standard()
			.containers(stack)
			.stream()
			.filter(own::containsKey)
			.map(own::get)
			.findFirst()
			.orElse(Experiment.DEFAULT_POOL);
	}

	// the pools above the services: the settings the services share
	private static List<Integer> sharedPools() {
		List<Integer> pools = new ArrayList<>();
		for (String line : LINES.subList(0, SERVICES)) {
			Matcher pool = POOL.matcher(line);
			if (pool.matches()) {
				pools.add(Integer.parseInt(pool.group(1)));
			}
		}
		return pools;
	}

	// the pool of each service that sets its own, over the one it starts from
	private static Map<String, Integer> ownPools() {
		Map<String, Integer> pools = new LinkedHashMap<>();
		String service = "";
		for (String line : LINES.subList(SERVICES, LINES.size())) {
			Matcher name = SERVICE.matcher(line);
			Matcher pool = POOL.matcher(line);
			if (name.matches()) {
				service = name.group(1);
			}
			else if (pool.matches()) {
				pools.put(service, Integer.parseInt(pool.group(1)));
			}
		}
		return pools;
	}

	private static List<String> lines() {
		try {
			return Files.readAllLines(RepositoryLayout.current().compose(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
