package io.archsim.benchmarks.docker;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One container's cgroup v2 counters at one moment.
 *
 * @param seconds when, in seconds since the epoch: halfway through the read
 * @param usageUsec the CPU time it has used, in microseconds
 * @param workingSet its working set in bytes: {@code memory.current} less
 * {@code inactive_file}, the page cache the kernel would reclaim first, as Kubernetes and
 * cAdvisor count it
 * @param anon its anonymous memory in bytes (heaps and stacks): what its processes
 * allocated
 */
public record CgroupReading(double seconds, @Nullable Long usageUsec, @Nullable Long workingSet, @Nullable Long anon) {

	/**
	 * A reading from {@code cpu.stat}, {@code memory.stat} and {@code memory.current} one
	 * after another.
	 * @param seconds when it was read
	 * @param text the three files' text
	 * @return the reading; a counter the text does not hold is null
	 */
	public static CgroupReading of(double seconds, String text) {
		Map<String, Long> counters = counters(text);
		Long current = counters.get("current");
		Long workingSet = (current != null) ? Math.max(0, current - counters.getOrDefault("inactive_file", 0L)) : null;
		return new CgroupReading(seconds, counters.get("usage_usec"), workingSet, counters.get("anon"));
	}

	// every "key value" line, the first of each key, and memory.current's bare number as
	// "current"
	private static Map<String, Long> counters(String text) {
		Map<String, Long> counters = new HashMap<>();
		for (String line : text.lines().toList()) {
			String[] parts = line.strip().split("\\s+", -1);
			if (parts.length == 2 && isDigits(parts[1])) {
				counters.putIfAbsent(parts[0], Long.parseLong(parts[1]));
			}
			else if (parts.length == 1 && isDigits(parts[0])) {
				counters.put("current", Long.parseLong(parts[0]));
			}
		}
		return counters;
	}

	private static boolean isDigits(String text) {
		return !text.isEmpty() && text.chars().allMatch((character) -> character >= '0' && character <= '9');
	}

}
