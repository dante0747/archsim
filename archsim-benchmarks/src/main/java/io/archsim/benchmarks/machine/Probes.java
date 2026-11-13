package io.archsim.benchmarks.machine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.ProcessResult;
import io.archsim.benchmarks.docker.Ticker;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.PythonJsonReader;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The contention check's probes: containers that each do a fixed unit of V8 work (20 JSON
 * round trips of a small record, like a request's, {@code probe.js}) on one CPU for a
 * time, from a moment every container of a condition shares, a share of every 10 ms; each
 * prints the units it did and its CPU time, so CPU per unit is the probe's cost and units
 * per wall second its speed.
 *
 * <p>
 * The script reaches node through an environment variable, in base64: Windows' command
 * line cannot carry its quotes intact as an argument, as run.py's did.
 */
final class Probes {

	/** The probe's runtime: the benchmark service's base image, so its work is V8's. */
	static final String IMAGE = "node:24-alpine";

	/** What every probe container's name starts with. */
	static final String PREFIX = "archsim-bench-probe-";

	private static final String CODE = Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_8));

	/**
	 * What node runs: the script, decoded from its variable and evaluated at global
	 * scope, as {@code node -e} runs a script.
	 */
	private static final String EVAL = "(0,eval)(atob(process.env.PROBE_CODE))";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final String NICEST = "19";

	/**
	 * The VM's clock was read near the end of the call: the container's start dominates
	 * it.
	 */
	private static final double CLOCK_READ_AT = 0.75;

	private static final int COST_DECIMALS = 4;

	private static final int SPEED_DECIMALS = 3;

	private final Compose compose;

	private final Ticker ticker;

	/**
	 * Probes run through Docker.
	 * @param compose what runs Docker's commands
	 * @param ticker the host's clock
	 */
	Probes(Compose compose, Ticker ticker) {
		this.compose = compose;
		this.ticker = ticker;
	}

	/**
	 * Starts a probe container pinned to a CPU.
	 * @param probe the probe
	 * @throws IllegalStateException when Docker did not start it
	 */
	void launch(Probe probe) {
		List<String> command = new ArrayList<>(List.of("run", "-d", "--name", PREFIX + probe.name(), "--cpuset-cpus",
				String.valueOf(probe.cpu()), "-e", "PROBE_S=" + PythonFormat.format(probe.seconds(), "g"), "-e",
				"DUTY=" + PythonFormat.format(probe.duty(), "g"), "-e",
				"START_AT=" + PythonFormat.format(probe.startAt(), ".0f"), "-e", "PROBE_CODE=" + CODE));
		command.addAll(probe.nice() ? List.of("--entrypoint", "nice", IMAGE, "-n", NICEST, "node", "-e", EVAL)
				: List.of("--entrypoint", "node", IMAGE, "-e", EVAL));
		ProcessResult started = this.compose.docker(command.toArray(String[]::new));
		if (!started.succeeded()) {
			throw new IllegalStateException("docker could not start probe " + probe.name() + ": " + started.errors());
		}
	}

	/**
	 * Waits for a probe to end and reads the line it printed; the container is removed
	 * either way.
	 * @param name the probe's name
	 * @return its units, CPU and wall time; empty when it printed none
	 */
	Optional<JsonNode> collect(String name) {
		String container = PREFIX + name;
		try {
			this.compose.docker("wait", container);
			List<String> lines = this.compose.docker("logs", container).output().strip().lines().toList();
			String last = lines.isEmpty() ? "" : lines.getLast();
			return (!last.isEmpty() && last.charAt(0) == '{') ? Optional.of(PythonJsonReader.read(last))
					: Optional.empty();
		}
		catch (JsonDecodeException ex) {
			return Optional.empty();
		}
		finally {
			this.compose.docker("rm", "-f", container);
		}
	}

	/**
	 * Removes every probe container a check left behind.
	 */
	void clear() {
		List<String> containers = List
			.of(this.compose.docker("ps", "-aq", "--filter", "name=" + PREFIX).output().strip().split("\\s+"))
			.stream()
			.filter((container) -> !container.isEmpty())
			.toList();
		if (!containers.isEmpty()) {
			List<String> command = new ArrayList<>(List.of("rm", "-f"));
			command.addAll(containers);
			this.compose.docker(command.toArray(String[]::new));
		}
	}

	/**
	 * Docker's VM's clock minus this host's, read through a short-lived container.
	 * @return the offset, in milliseconds; 0 when the container printed no time
	 */
	double vmClockOffsetMs() {
		double before = this.ticker.now() * MS_PER_S;
		String printed = this.compose
			.docker("run", "--rm", "--entrypoint", "node", IMAGE, "-e", "console.log(Date.now())")
			.output()
			.strip();
		double after = this.ticker.now() * MS_PER_S;
		List<String> lines = printed.lines().toList();
		try {
			double vm = Double.parseDouble(lines.isEmpty() ? "" : lines.getLast());
			return vm - (before + CLOCK_READ_AT * (after - before));
		}
		catch (NumberFormatException ex) {
			return 0.0;
		}
	}

	/**
	 * A probe's cost and speed from what it printed.
	 * @param printed its units, CPU and wall time
	 * @return its units, its lateness, its CPU per unit in microseconds, its units per
	 * wall millisecond and its busy share; empty without its units or times
	 */
	static Optional<ObjectNode> rates(JsonNode printed) {
		JsonNode units = printed.path("units");
		JsonNode cpuMs = printed.path("cpuMs");
		JsonNode wallMs = printed.path("wallMs");
		if (!PythonText.truthy(units) || !PythonText.truthy(cpuMs) || !PythonText.truthy(wallMs)) {
			return Optional.empty();
		}
		ObjectNode rates = NODES.objectNode().set("units", units);
		rates.set("lateMs", PythonValues.get(printed, "lateMs"));
		rates.put("cpuUsPerUnit",
				PythonRound.round(cpuMs.doubleValue() * MS_PER_S / units.doubleValue(), COST_DECIMALS));
		rates.put("unitsPerWallMs", PythonRound.round(units.doubleValue() / wallMs.doubleValue(), SPEED_DECIMALS));
		rates.put("busy", PythonRound.round(cpuMs.doubleValue() / wallMs.doubleValue(), COST_DECIMALS));
		return Optional.of(rates);
	}

	private static String script() {
		try (InputStream in = Probes.class.getResourceAsStream("probe.js")) {
			if (in == null) {
				throw new IllegalStateException("missing resource probe.js");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A probe container.
	 *
	 * @param name its name, after {@link #PREFIX}
	 * @param cpu the CPU it is pinned to
	 * @param duty the share of every 10 ms it works, 1 all the time
	 * @param seconds how long it works
	 * @param startAt when it starts, on the VM's clock in milliseconds; 0 at once
	 * @param nice whether it runs at the lowest priority, taking only what the CPU's
	 * other work leaves idle
	 */
	record Probe(String name, int cpu, double duty, double seconds, double startAt, boolean nice) {

	}

}
