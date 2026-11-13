package io.archsim.benchmarks.docker;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.model.text.PythonFormat;

/**
 * Starts a stack afresh, its volumes removed first, and stops it: the CPUs k6 gets on it,
 * the cache the service is told of and the rows PostgreSQL loads are set for compose.
 */
public final class StackLifecycle {

	/** Rows {@code init.sql} loads; a stack whose keyspace is larger gets the rest. */
	static final int DEFAULT_ITEMS = 100_000;

	/** How long a started service may take to answer its health check. */
	private static final double HEALTH_S = 120;

	/** Kafka's consumers joining their group. */
	private static final double CONSUMER_JOIN_S = 5;

	private static final double RETRY_S = 1;

	private final Instruments instruments;

	private final Cpusets cpusets;

	private final Map<String, String> environment;

	private final HarnessLog log;

	/**
	 * The stacks of a compose file.
	 * @param instruments what drives and reads them
	 * @param cpusets the cpusets the compose file declares
	 * @param environment the harness's environment, which may name the CPUs
	 * @param log the log
	 */
	public StackLifecycle(Instruments instruments, Cpusets cpusets, Map<String, String> environment, HarnessLog log) {
		this.instruments = instruments;
		this.cpusets = cpusets;
		this.environment = Map.copyOf(environment);
		this.log = log;
	}

	/**
	 * Starts a stack, once its last run's containers and volumes are gone, and waits
	 * until its services answer.
	 * @param stack the stack
	 * @return the instruments on it, its variables set for compose
	 */
	public Instruments up(String stack) {
		Compose compose = this.instruments.compose().with(variables(stack));
		this.log.line("starting stack '" + stack + "'");
		compose.result("--profile", stack, "down", "-v", "--remove-orphans");
		compose.output("--profile", stack, "up", "-d", "--build", "--wait");
		Instruments running = this.instruments.on(compose);
		waitFor(running, ServiceHttp.API + "/health", HEALTH_S);
		if ("kafka".equals(stack)) {
			waitFor(running, ServiceHttp.CONSUMER + "/health", HEALTH_S);
			running.ticker().sleep(CONSUMER_JOIN_S);
		}
		return running;
	}

	// the CPUs k6 gets, the cache the service is told of (the cache stack's only, as
	// run.py set it) and the rows PostgreSQL loads
	private Map<String, String> variables(String stack) {
		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("BENCH_CPUS_K6", this.cpusets.of(stack, this.environment).get("k6"));
		variables.put("BENCH_REDIS_URL", "cache".equals(stack) ? "redis://redis:6379" : "");
		variables.put("BENCH_ITEMS", String.valueOf(items(stack)));
		return variables;
	}

	/**
	 * Stops a stack and removes its containers and volumes.
	 * @param stack the stack
	 */
	public void down(String stack) {
		this.log.line("stopping stack '" + stack + "'");
		this.instruments.compose().result("--profile", stack, "down", "-v", "--remove-orphans");
	}

	/**
	 * The rows of items PostgreSQL loads for a stack: enough for every key its
	 * experiments name.
	 * @param stack the stack
	 * @return the rows
	 */
	static int items(String stack) {
		Experiments experiments = Experiments.standard();
		List<String> onTheStack = experiments.stacks().get(stack);
		if (onTheStack == null) {
			throw new IllegalArgumentException("no stack " + stack);
		}
		return onTheStack.stream()
			.mapToInt((experiment) -> experiments.get(experiment).keys())
			.reduce(DEFAULT_ITEMS, Math::max);
	}

	/**
	 * Waits until an endpoint answers.
	 * @param instruments what asks it
	 * @param url the endpoint
	 * @param seconds the longest wait
	 * @throws HarnessStopException when it did not answer in time
	 */
	public static void waitFor(Instruments instruments, String url, double seconds) {
		Ticker ticker = instruments.ticker();
		double deadline = ticker.now() + seconds;
		while (ticker.now() < deadline) {
			try {
				instruments.services().get(url);
				return;
			}
			catch (UncheckedIOException ex) {
				ticker.sleep(RETRY_S);
			}
		}
		throw new HarnessStopException(url + " did not answer within " + PythonFormat.format(seconds, ".0f") + " s");
	}

}
