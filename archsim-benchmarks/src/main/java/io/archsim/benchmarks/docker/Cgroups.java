package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The containers' cgroup v2 counters: from the host when it can read them (Linux with
 * cgroup v2, as on GitHub's runners), else through {@code docker exec}, a {@code cat} in
 * the container that costs it about a millisecond of CPU (Docker Desktop runs the
 * containers in a VM).
 */
public final class Cgroups {

	private static final List<String> FILES = List.of("cpu.stat", "memory.stat", "memory.current");

	private static final String CPU_STAT = "cpu.stat";

	private final Compose compose;

	private final Path host;

	private final Ticker ticker;

	/**
	 * The counters of a machine's containers.
	 * @param compose what runs Docker
	 * @param host the host's cgroup folder ({@code /sys/fs/cgroup})
	 * @param ticker the clock that times each reading
	 */
	public Cgroups(Compose compose, Path host, Ticker ticker) {
		this.compose = compose;
		this.host = host;
		this.ticker = ticker;
	}

	/**
	 * The same counters, read through a stack's compose.
	 * @param stack the stack's compose
	 * @return the counters
	 */
	public Cgroups on(Compose stack) {
		return new Cgroups(stack, this.host, this.ticker);
	}

	/**
	 * A container's CPU and memory counters now.
	 * @param container the container's id
	 * @return its reading, timed halfway through it
	 */
	public CgroupReading read(String container) {
		double start = this.ticker.now();
		String text = hostFolder(container).map(Cgroups::hostText).orElseGet(() -> {
			List<String> command = new ArrayList<>(List.of("exec", container, "cat"));
			FILES.forEach((file) -> command.add("/sys/fs/cgroup/" + file));
			return this.compose.docker(command.toArray(String[]::new)).output();
		});
		return CgroupReading.of((start + this.ticker.now()) / 2, text);
	}

	/**
	 * The CPU time a stack's service has used, in its container.
	 * @param service the compose service
	 * @return microseconds; empty when it has no container or its counter cannot be read
	 */
	public Optional<Long> usageUsec(String service) {
		return this.compose.container(service).flatMap((container) -> {
			String stat = this.compose.docker("exec", container, "cat", "/sys/fs/cgroup/" + CPU_STAT).output();
			return Optional.ofNullable(CgroupReading.of(0, stat).usageUsec());
		});
	}

	/**
	 * The CPU time each of a stack's services has used.
	 * @param services the compose services
	 * @return microseconds, of those whose counter could be read
	 */
	public Map<String, Long> usageUsec(List<String> services) {
		Map<String, Long> usage = new HashMap<>();
		services.forEach((service) -> usageUsec(service).ifPresent((used) -> usage.put(service, used)));
		return usage;
	}

	// the container's cgroup folder on this host, when it can see it
	private Optional<Path> hostFolder(String container) {
		return Stream
			.of(this.host.resolve("system.slice").resolve("docker-" + container + ".scope"),
					this.host.resolve("docker").resolve(container))
			.filter((folder) -> Files.isRegularFile(folder.resolve(CPU_STAT)))
			.findFirst();
	}

	private static String hostText(Path folder) {
		List<String> texts = new ArrayList<>();
		for (String file : FILES) {
			Path path = folder.resolve(file);
			if (Files.isRegularFile(path)) {
				texts.add(text(path));
			}
		}
		return String.join("\n", texts);
	}

	private static String text(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
