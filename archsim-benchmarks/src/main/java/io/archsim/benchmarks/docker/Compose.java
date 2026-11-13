package io.archsim.benchmarks.docker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.benchmarks.HarnessLog;

/**
 * {@code docker compose} with the benchmark's stacks ({@code benchmarks/compose.yaml}),
 * and {@code docker} itself, through one runner of commands.
 */
public final class Compose {

	/**
	 * How much of a failing command's output the log keeps, of each stream, from its end.
	 */
	private static final int TAIL = 4000;

	/** How many of a failing command's arguments its log line names. */
	private static final int NAMED_ARGUMENTS = 4;

	private static final String REDIS_URL = "BENCH_REDIS_URL";

	private final ProcessRunner runner;

	private final Path file;

	private final Map<String, String> environment;

	private final HarnessLog log;

	private Compose(ProcessRunner runner, Path file, Map<String, String> environment, HarnessLog log) {
		this.runner = runner;
		this.file = file;
		this.environment = Map.copyOf(environment);
		this.log = log;
	}

	/**
	 * The stacks of a compose file, the cache's address set for compose (empty unless the
	 * harness's environment names one).
	 * @param runner what runs Docker
	 * @param file the compose file
	 * @param environment the harness's environment
	 * @param log the log, which shows what a failing command printed
	 * @return the stacks
	 */
	public static Compose of(ProcessRunner runner, Path file, Map<String, String> environment, HarnessLog log) {
		return new Compose(runner, file, Map.of(REDIS_URL, environment.getOrDefault(REDIS_URL, "")), log);
	}

	/**
	 * The same stacks with more variables set for compose: a stack's CPUs, cache and
	 * rows.
	 * @param variables the variables, over the ones set already
	 * @return the stacks
	 */
	public Compose with(Map<String, String> variables) {
		Map<String, String> set = new HashMap<>(this.environment);
		set.putAll(variables);
		return new Compose(this.runner, this.file, set, this.log);
	}

	/**
	 * What a compose command printed, once it succeeded.
	 * @param arguments its arguments
	 * @return what it printed out
	 * @throws IllegalStateException when it fails, after the log shows what it printed
	 */
	public String output(String... arguments) {
		ProcessResult result = result(arguments);
		if (!result.succeeded()) {
			this.log.line("docker compose "
					+ String.join(" ", List.of(arguments).subList(0, Math.min(NAMED_ARGUMENTS, arguments.length)))
					+ " failed (exit " + result.exitCode() + "):");
			this.log.text(tail(result.output()) + "\n" + tail(result.errors()));
			throw new IllegalStateException(
					"docker compose " + String.join(" ", arguments) + " failed with exit code " + result.exitCode());
		}
		return result.output();
	}

	/**
	 * What a compose command did, whether it succeeded or not.
	 * @param arguments its arguments
	 * @return its exit code and what it printed
	 */
	public ProcessResult result(String... arguments) {
		return this.runner.run(command(arguments), this.environment, "");
	}

	/**
	 * What a compose command did with a script as its input
	 * ({@code exec -T <service> sh -s}).
	 * @param script the script
	 * @param arguments its arguments
	 * @return its exit code and what it printed
	 */
	public ProcessResult withInput(String script, String... arguments) {
		return this.runner.run(command(arguments), this.environment, script);
	}

	/**
	 * The container of a stack's service.
	 * @param service the compose service
	 * @return its container's id; empty when it is not running
	 */
	public Optional<String> container(String service) {
		String id = output("ps", "-q", service).strip();
		return id.isEmpty() ? Optional.empty() : Optional.of(id);
	}

	/**
	 * What a {@code docker} command did, outside compose: {@code docker exec},
	 * {@code docker version}.
	 * @param arguments its arguments
	 * @return its exit code and what it printed
	 */
	public ProcessResult docker(String... arguments) {
		return dockerWithInput("", arguments);
	}

	/**
	 * What a {@code docker} command did with a script as its input ({@code docker run -i
	 * ... sh -s}).
	 * @param script the script
	 * @param arguments its arguments
	 * @return its exit code and what it printed
	 */
	public ProcessResult dockerWithInput(String script, String... arguments) {
		List<String> command = new ArrayList<>(List.of("docker"));
		command.addAll(List.of(arguments));
		return this.runner.run(command, Map.of(), script);
	}

	private List<String> command(String... arguments) {
		List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", this.file.toString()));
		command.addAll(List.of(arguments));
		return command;
	}

	private static String tail(String text) {
		return text.substring(Math.max(0, text.length() - TAIL));
	}

}
