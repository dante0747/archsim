package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The CPUs each container is pinned to: its {@code cpuset} in {@code compose.yaml}, a
 * {@code BENCH_CPUS_*} variable over a default, and k6's from the runner.
 */
public final class Cpusets {

	private static final Pattern SERVICE = Pattern.compile("^  ([\\w-]+):\\s*$");

	private static final Pattern CPUSET = Pattern.compile("^\\s+cpuset:\\s*\"\\$\\{(\\w+):-([^}]*)\\}\"");

	/** The variable that gives k6's CPUs over the runner's default. */
	private static final String K6_VARIABLE = "BENCH_CPUS_K6";

	private final Map<String, Declared> declared;

	private Cpusets(Map<String, Declared> declared) {
		this.declared = declared;
	}

	/**
	 * The cpusets {@code compose.yaml} declares.
	 * @param compose the compose file
	 * @return its cpusets
	 */
	public static Cpusets declaredIn(Path compose) {
		Map<String, Declared> declared = new HashMap<>();
		String service = null;
		try {
			for (String line : Files.readAllLines(compose, StandardCharsets.UTF_8)) {
				Matcher name = SERVICE.matcher(line);
				Matcher cpuset = CPUSET.matcher(line);
				if (name.matches()) {
					service = name.group(1);
				}
				else if (cpuset.find() && service != null) {
					declared.put(service, new Declared(cpuset.group(1), cpuset.group(2)));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new Cpusets(declared);
	}

	/**
	 * The variables that set the cpusets, one a container: {@code BENCH_CPUS_POSTGRES}
	 * and the like, as a runner with other CPUs sets them.
	 * @return the variables
	 */
	public Set<String> variables() {
		return this.declared.values().stream().map(Declared::variable).collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * The CPUs each container of a stack is pinned to, k6 last: each one's variable when
	 * set, else its default.
	 * @param stack the stack
	 * @param environment the variables the runner sees
	 * @return each container's cpuset, in the stack's order
	 */
	public Map<String, String> of(String stack, Map<String, String> environment) {
		Map<String, String> sets = new LinkedHashMap<>();
		for (String container : Stacks.standard().containers(stack)) {
			Declared cpuset = this.declared.get(container);
			if (cpuset == null) {
				throw new IllegalStateException("compose.yaml declares no cpuset for " + container);
			}
			sets.put(container, nonEmpty(environment.get(cpuset.variable()), cpuset.fallback()));
		}
		sets.put("k6", nonEmpty(environment.get(K6_VARIABLE), Stacks.standard().k6Cpus(stack)));
		return sets;
	}

	/**
	 * The CPUs a cpuset names: {@code 0,1,3} or {@code 0-3}.
	 * @param cpuset the cpuset
	 * @return its CPUs
	 */
	public static List<Integer> cpus(String cpuset) {
		List<Integer> cpus = new ArrayList<>();
		for (String part : cpuset.split(",", -1)) {
			String[] range = part.strip().split("-", 2);
			int first = Integer.parseInt(range[0]);
			int last = (range.length == 2) ? Integer.parseInt(range[1]) : first;
			for (int cpu = first; cpu <= last; cpu++) {
				cpus.add(cpu);
			}
		}
		return cpus;
	}

	/**
	 * How many CPUs a cpuset names.
	 * @param cpuset the cpuset
	 * @return the count
	 */
	public static int count(String cpuset) {
		return cpus(cpuset).size();
	}

	private static String nonEmpty(@Nullable String value, String fallback) {
		return (value != null && !value.isEmpty()) ? value : fallback;
	}

	private record Declared(String variable, String fallback) {

	}

}
