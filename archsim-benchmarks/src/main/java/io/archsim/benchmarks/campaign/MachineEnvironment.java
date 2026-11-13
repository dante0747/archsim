package io.archsim.benchmarks.campaign;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.docker.ProcessRunner;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The machine a run measures on, as {@code environment.json} records it: its CPU and
 * name, its operating system as Python's {@code platform.platform()} wrote it (the range
 * fit compares runs by it), Windows' power mode, Docker's version, CPUs and memory, the
 * images and the harness's Java.
 */
final class MachineEnvironment {

	/**
	 * Windows 11's power modes (Settings, System, Power), by the overlay scheme each
	 * sets.
	 */
	static final Map<String, String> POWER_MODES = Map.of("961cc777-2547-4f9d-8174-7d86181b8a7a",
			"Best power efficiency", "00000000-0000-0000-0000-000000000000", "Balanced",
			"ded574b5-45a0-4f42-8737-46345c09c238", "Best performance");

	/** The first build of Windows 11, which {@code platform} names release 11 from. */
	private static final int WINDOWS_11_BUILD = 22000;

	private static final Pattern WINDOWS_VERSION = Pattern
		.compile("\\[Version (?<major>\\d+)\\.(?<minor>\\d+)\\.(?<build>\\d+)");

	private static final Pattern OVERLAY_SCHEME = Pattern.compile("ActiveOverlayAcPowerScheme\\s+REG_SZ\\s+(\\S+)");

	private static final String POWER_SCHEMES = "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Power\\User\\PowerSchemes";

	private final ProcessRunner runner;

	private final Map<String, String> environment;

	/**
	 * The environment of this machine.
	 * @param runner what runs Docker and the operating system's tools
	 * @param environment the harness's environment, which may name the machine
	 */
	MachineEnvironment(ProcessRunner runner, Map<String, String> environment) {
		this.runner = runner;
		this.environment = Map.copyOf(environment);
	}

	/**
	 * What {@code environment.json} records of the machine, before an experiment's own.
	 * @return {@code cpu}, {@code machineId}, {@code os}, {@code powerMode},
	 * {@code docker}, {@code dockerCpus}, {@code dockerMemory}, {@code images} and
	 * {@code java}
	 */
	ObjectNode of() {
		String cpu = MachineName.cpu();
		ObjectNode machine = JsonNodeFactory.instance.objectNode().put("cpu", cpu);
		machine.put("machineId", MachineName.id(this.environment, cpu));
		machine.put("os", operatingSystem());
		machine.set("powerMode",
				powerMode().<JsonNode>map(JsonNodeFactory.instance::stringNode).orElse(PythonValues.none()));
		machine.put("docker",
				run("docker", "version", "--format", "{{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})"));
		machine.put("dockerCpus", run("docker", "info", "--format", "{{.NCPU}}"));
		machine.put("dockerMemory", run("docker", "info", "--format", "{{.MemTotal}}"));
		machine.putObject("images")
			.put("postgres", "postgres:16-alpine")
			.put("redis", "redis:7-alpine")
			.put("kafka", "apache/kafka:3.8.0")
			.put("k6", "grafana/k6:1.3.0")
			.put("service", Experiments.standard().runtime("nodejs").image());
		machine.put("java", Runtime.version().toString());
		return machine;
	}

	/**
	 * The image of an experiment's service, as {@code environment.json} records it.
	 * @param experiment the experiment
	 * @return its image; chain's names its gateway and API
	 */
	static String image(Experiment experiment) {
		String image = Experiments.standard().runtime(experiment).image();
		return "chain".equals(experiment.stack()) ? image + " (gateway and API)" : image;
	}

	/**
	 * The operating system as {@code platform.platform()} names it on Windows.
	 * @param ver what {@code ver} printed
	 * @return {@code Windows-11-10.0.26300-SP0}; empty when it named no version
	 */
	static Optional<String> windowsPlatform(String ver) {
		Matcher version = WINDOWS_VERSION.matcher(ver);
		if (!version.find()) {
			return Optional.empty();
		}
		int build = Integer.parseInt(version.group("build"));
		String release = (build >= WINDOWS_11_BUILD) ? "11" : version.group("major");
		return Optional.of("Windows-" + release + "-" + version.group("major") + "." + version.group("minor") + "."
				+ version.group("build") + "-SP0");
	}

	/**
	 * Windows' power mode on mains power, from the registry.
	 * @param query what {@code reg query} printed of the power schemes
	 * @return its name, else its scheme; empty when the query named none
	 */
	static Optional<String> powerModeOf(String query) {
		Matcher scheme = OVERLAY_SCHEME.matcher(query);
		if (!scheme.find()) {
			return Optional.empty();
		}
		String guid = scheme.group(1);
		return Optional.of(POWER_MODES.getOrDefault(guid.toLowerCase(Locale.ROOT), guid));
	}

	// platform.platform(): Windows' from ver, else the system, its release and machine
	private String operatingSystem() {
		String name = System.getProperty("os.name", "");
		if (name.startsWith("Windows")) {
			return windowsPlatform(run("cmd", "/c", "ver")).orElse(name);
		}
		String machine = System.getProperty("os.arch", "").replace("amd64", "x86_64");
		String libc = run("getconf", "GNU_LIBC_VERSION").replace("glibc ", "glibc");
		return name + "-" + System.getProperty("os.version", "") + "-" + machine
				+ (libc.isEmpty() ? "" : "-with-" + libc);
	}

	// the power mode, Windows' only: it moves the knee (Best performance raised
	// pg-mixed's from 2,430 to about 4,000 req/s), so runs under different modes are not
	// repeats of each other
	private Optional<String> powerMode() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return Optional.empty();
		}
		return powerModeOf(run("reg", "query", POWER_SCHEMES, "/v", "ActiveOverlayAcPowerScheme"));
	}

	// a command's output, stripped; empty when it fails to start
	private String run(String... command) {
		try {
			return this.runner.run(List.of(command), Map.of(), "").output().strip();
		}
		catch (UncheckedIOException ex) {
			return "";
		}
	}

}
