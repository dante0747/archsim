package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a stack's cpusets fall on physical cores: hyperthread siblings slow each other
 * down, so {@code environment.json} records each container's cores and the containers
 * that share one.
 */
public final class CpuTopology {

	/**
	 * Lists each CPU of Docker's VM and the CPUs on its physical core, itself included.
	 */
	static final String SIBLINGS_SCRIPT = "for c in /sys/devices/system/cpu/cpu[0-9]*; do "
			+ "echo \"${c##*cpu} $(cat $c/topology/thread_siblings_list)\"; done\n";

	private static final Comparator<List<Integer>> CORE_ORDER = (left, right) -> {
		for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
			int order = Integer.compare(left.get(index), right.get(index));
			if (order != 0) {
				return order;
			}
		}
		return Integer.compare(left.size(), right.size());
	};

	private CpuTopology() {
	}

	/**
	 * Each CPU of Docker's VM and the CPUs on its physical core, as the kernel inside the
	 * VM reports them.
	 * @param compose what runs Docker
	 * @return the siblings; empty when Docker cannot say
	 */
	public static Optional<Map<Integer, List<Integer>>> siblings(Compose compose) {
		return siblings(compose
			.dockerWithInput(SIBLINGS_SCRIPT, "run", "--rm", "-i", "--entrypoint", "sh", "postgres:16-alpine", "-s")
			.output());
	}

	/**
	 * The siblings a listing names: one line per CPU, the CPU and its core's cpuset.
	 * @param listing what the script printed
	 * @return each CPU's core; empty when the listing names none
	 */
	static Optional<Map<Integer, List<Integer>>> siblings(String listing) {
		Map<Integer, List<Integer>> siblings = new TreeMap<>();
		for (String line : listing.lines().toList()) {
			String[] parts = line.strip().split(" ", 2);
			if (parts.length == 2 && !parts[0].isEmpty() && parts[0].chars().allMatch(Character::isDigit)
					&& !parts[1].isEmpty()) {
				siblings.put(Integer.parseInt(parts[0]), Cpusets.cpus(parts[1]));
			}
		}
		return siblings.isEmpty() ? Optional.empty() : Optional.of(siblings);
	}

	/**
	 * A CPU's physical core, named by its CPUs.
	 * @param cpu the CPU
	 * @param siblings each CPU's siblings
	 * @return the core's CPUs, in order; the CPU alone when the siblings do not name it
	 */
	public static List<Integer> coreOf(int cpu, Map<Integer, List<Integer>> siblings) {
		return List.copyOf(new TreeSet<>(siblings.getOrDefault(cpu, List.of(cpu))));
	}

	/**
	 * How cpusets fall on physical cores.
	 * @param sets each container's cpuset, in the stack's order, k6 last
	 * @param siblings each CPU's siblings
	 * @return {@code siblings}, each container's {@code physicalCores}, and the
	 * {@code sharedCores} of each pair of containers that share one
	 */
	public static ObjectNode of(Map<String, String> sets, Map<Integer, List<Integer>> siblings) {
		Map<String, Set<List<Integer>>> cores = new LinkedHashMap<>();
		sets.forEach((name, cpuset) -> {
			Set<List<Integer>> owned = new TreeSet<>(CORE_ORDER);
			Cpusets.cpus(cpuset).forEach((cpu) -> owned.add(coreOf(cpu, siblings)));
			cores.put(name, owned);
		});
		ObjectNode topology = JsonNodeFactory.instance.objectNode();
		ObjectNode listed = topology.putObject("siblings");
		new TreeMap<>(siblings).forEach((cpu, core) -> listed.set(String.valueOf(cpu), array(core)));
		ObjectNode counts = topology.putObject("physicalCores");
		cores.forEach((name, owned) -> counts.put(name, owned.size()));
		topology.set("sharedCores", shared(cores));
		return topology;
	}

	// each pair of containers, in their order, that share a physical core
	private static ArrayNode shared(Map<String, Set<List<Integer>>> cores) {
		ArrayNode shared = JsonNodeFactory.instance.arrayNode();
		List<String> names = new ArrayList<>(cores.keySet());
		for (int first = 0; first < names.size(); first++) {
			for (String second : names.subList(first + 1, names.size())) {
				Set<List<Integer>> both = new TreeSet<>(CORE_ORDER);
				both.addAll(cores.get(names.get(first)));
				both.retainAll(cores.get(second));
				if (!both.isEmpty()) {
					ObjectNode pair = shared.addObject();
					pair.putArray("containers").add(names.get(first)).add(second);
					ArrayNode named = pair.putArray("cores");
					both.forEach((core) -> named.add(array(core)));
				}
			}
		}
		return shared;
	}

	private static ArrayNode array(List<Integer> cpus) {
		ArrayNode array = JsonNodeFactory.instance.arrayNode();
		cpus.forEach(array::add);
		return array;
	}

}
