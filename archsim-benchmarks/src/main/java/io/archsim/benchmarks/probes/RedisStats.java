package io.archsim.benchmarks.probes;

import java.util.Set;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.docker.Compose;

/**
 * Redis's own command statistics ({@code INFO commandstats}): each command's calls and
 * the microseconds Redis spent on them.
 */
public final class RedisStats {

	private static final String PREFIX = "cmdstat_";

	private static final Set<String> KEPT = Set.of("calls", "usec");

	private RedisStats() {
	}

	/**
	 * The cache stack's Redis's command statistics now.
	 * @param compose the stack's compose
	 * @return each command's {@code calls} and {@code usec}
	 */
	public static ObjectNode read(Compose compose) {
		return of(compose.output("exec", "-T", "redis", "redis-cli", "INFO", "commandstats"));
	}

	/**
	 * The command statistics {@code INFO commandstats} printed.
	 * @param info what it printed
	 * @return each command's {@code calls} and {@code usec}, as floats
	 */
	static ObjectNode of(String info) {
		ObjectNode stats = JsonNodeFactory.instance.objectNode();
		for (String line : info.lines().toList()) {
			if (!line.startsWith(PREFIX)) {
				continue;
			}
			int colon = line.indexOf(':');
			ObjectNode command = stats.putObject(line.substring(PREFIX.length(), colon));
			for (String pair : line.substring(colon + 1).split(",", -1)) {
				String[] keyAndValue = pair.split("=", -1);
				if (keyAndValue.length != 2) {
					throw new IllegalArgumentException("not a statistic: " + pair);
				}
				if (KEPT.contains(keyAndValue[0])) {
					command.put(keyAndValue[0], Double.parseDouble(keyAndValue[1].strip()));
				}
			}
		}
		return stats;
	}

}
