package io.archsim.engine.sizing.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A run's bottlenecks and memory against auto-sizing: no auto-sized part is the
 * bottleneck because of its CPU or memory, and each auto-sized part's simulated memory
 * peak is at most the memory it needs, which is at most its instance's.
 */
final class ResourceChecks {

	// the report rounds a memory peak to 3 decimals
	private static final double PEAK_ROUNDING = 1e-3;

	private static final double TOLERANCE = 1e-6;

	private static final double KIB_PER_GIB = 1024.0 * 1024.0;

	private ResourceChecks() {
	}

	/**
	 * Each bottleneck finding's check: the part's limit is not a resource auto-sizing
	 * set.
	 * @param run the run
	 * @return the checks
	 */
	static List<AuditCheck> bottlenecks(AuditedRun run) {
		List<AuditCheck> checks = new ArrayList<>();
		for (JsonNode finding : run.report().path("findings")) {
			if (!AuditJson.is(finding.path("kind"), "bottleneck")) {
				continue;
			}
			String id = AuditJson.text(finding.path("nodeId"));
			String by = AuditJson.text(run.node(id).path("summary").path("capacityLimit"));
			String resource = switch (by) {
				case "cpu" -> "cpuCores";
				case "memory" -> "memoryGib";
				default -> "";
			};
			boolean bad = !resource.isEmpty()
					&& AuditJson.is(run.sizing(id).path("specs").path(resource).path("source"), "auto");
			checks
				.add(new AuditCheck(id, "bottleneck", !bad, "bottleneck limited by " + (by.isEmpty() ? "its slots" : by)
						+ (bad ? " (an auto-sized resource)" : " (not an auto-sized resource)")));
		}
		return checks;
	}

	/**
	 * Each part with auto-sized memory and a memory need: peak ≤ need ≤ instance.
	 * @param run the run
	 * @return the checks
	 */
	static List<AuditCheck> memory(AuditedRun run) {
		List<AuditCheck> checks = new ArrayList<>();
		for (JsonNode node : run.nodes()) {
			String id = AuditJson.text(node.path("id"));
			JsonNode entry = run.sizing(id);
			JsonNode most = AuditedRun.memoryNeed(entry.path("need"));
			if (AuditedRun.autoSized(entry, "memoryGib") && most.isNumber()) {
				checks.add(memory(id, entry, most.doubleValue(), peak(node, run.config(id))));
			}
		}
		return checks;
	}

	private static AuditCheck memory(String id, JsonNode entry, double most, @Nullable Double peak) {
		double instance = entry.path("specs").path("memoryGib").path("value").doubleValue();
		boolean ok = most <= instance + TOLERANCE && (peak == null || peak <= most + PEAK_ROUNDING);
		String shown = (peak != null) ? String.format(Locale.ROOT, "%.3f GiB", peak) : "not simulated";
		return new AuditCheck(id, "memory", ok,
				String.format(Locale.ROOT, "peak %s ≤ need %.3f GiB ≤ instance %s GiB", shown, most, instance));
	}

	// the peak the run simulated, or for a cache, its keys' size per shard
	private static @Nullable Double peak(JsonNode node, JsonNode config) {
		JsonNode summary = node.path("summary");
		if (summary.path("memoryPeakGiB").isNumber()) {
			return summary.path("memoryPeakGiB").doubleValue();
		}
		if (AuditJson.is(node.path("type"), "cache") && summary.path("keysCached").isNumber()) {
			return summary.path("keysCached").doubleValue() * AuditJson.number(config.path("itemSizeKb"), 1.0)
					/ KIB_PER_GIB / AuditJson.number(config.path("shards"), 1.0);
		}
		return null;
	}

}
