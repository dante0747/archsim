package io.archsim.engine.sizing.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;

/**
 * Auto-sizing as the run changes: a re-run of a run's input gives the same types and
 * cost, and at 1.5 and 2 times the request rate no part's instance gets cheaper or short
 * of the memory it needs. (vCPU alone is not the measure: a light part moves from a
 * 2-vCPU burstable type to a 1-vCPU full-core type as its load grows.)
 */
final class ScaleChecks {

	/** The reproducibility check runs each template this long, twice. */
	private static final double RERUN_SECONDS = 4.0;

	/** The multiples of the design's request rate auto-sizing is compared at, in turn. */
	private static final List<Double> RATE_MULTIPLES = List.of(1.0, 1.5, 2.0);

	private static final double CENTS = 0.01;

	private static final double TOLERANCE = 1e-9;

	private ScaleChecks() {
	}

	/**
	 * A short run of the design, then its input run again: the same types and cost.
	 * @param design the template's design
	 * @return the check
	 */
	static AuditCheck rerun(JsonNode design) {
		AuditedRun first = TemplateRuns.run(TemplateRuns.compressed(design, RERUN_SECONDS));
		AuditedRun again = TemplateRuns.run(first.report().path("input").deepCopy());
		Map<String, String> before = skus(first);
		Map<String, String> after = skus(again);
		boolean same = before.equals(after);
		return new AuditCheck("*", "rerun", same && Math.abs(again.monthly() - first.monthly()) < CENTS,
				String.format(Locale.ROOT, "types %s, cost $%,.2f -> $%,.2f", same ? "same" : before + " -> " + after,
						first.monthly(), again.monthly()));
	}

	/**
	 * Each part auto-sizing sizes, at 1 then 1.5 then 2 times the request rate: never a
	 * cheaper type, never less memory than it needs.
	 * @param design the template's design
	 * @return the checks
	 */
	static List<AuditCheck> monotonic(JsonNode design) {
		List<JsonNode> steps = new ArrayList<>();
		for (double multiple : RATE_MULTIPLES) {
			steps.add(TemplateRuns.sizing(TemplateRuns.scaled(design, multiple)));
		}
		List<AuditCheck> checks = new ArrayList<>();
		for (Map.Entry<String, JsonNode> part : steps.getFirst().properties()) {
			if (AuditJson.truthy(part.getValue().path("skipped"))) {
				continue;
			}
			List<String> shrank = IntStream.range(1, steps.size())
				.mapToObj((step) -> shrinking(steps.get(step - 1).path(part.getKey()),
						steps.get(step).path(part.getKey())))
				.flatMap(Optional::stream)
				.toList();
			checks.add(new AuditCheck(part.getKey(), "monotonic", shrank.isEmpty(),
					shrank.isEmpty() ? "never cheaper at 1.5x and 2x" : String.join("; ", shrank)));
		}
		return checks;
	}

	// how a part's size shrank from one rate to a higher one; empty when it did not
	private static Optional<String> shrinking(JsonNode lower, JsonNode higher) {
		if (!lower.path("specs").has("cpuCores") || !higher.path("specs").has("cpuCores")) {
			return Optional.empty();
		}
		boolean cheaper = cheaper(lower, higher);
		if (!cheaper && !shortOfMemory(higher)) {
			return Optional.empty();
		}
		return Optional.of(size(lower) + " -> " + size(higher) + (cheaper ? " (cheaper)" : " (memory below its need)"));
	}

	private static boolean cheaper(JsonNode lower, JsonNode higher) {
		JsonNode before = lower.path("pricePerHour");
		JsonNode after = higher.path("pricePerHour");
		return before.isNumber() && after.isNumber() && after.doubleValue() < before.doubleValue() - TOLERANCE;
	}

	// memory set on the part is the design's choice: only auto-sized memory
	// can fall short
	private static boolean shortOfMemory(JsonNode entry) {
		JsonNode memory = entry.path("specs").path("memoryGib");
		double gib = AuditJson.number(memory.path("value"), 0.0);
		JsonNode need = AuditedRun.memoryNeed(entry.path("need"));
		return AuditJson.is(memory.path("source"), "auto") && need.isNumber() && gib != 0
				&& gib < need.doubleValue() - TOLERANCE;
	}

	private static String size(JsonNode entry) {
		return entry.path("sku").isString() ? AuditJson.text(entry.path("sku"))
				: String.valueOf(entry.path("specs").path("cpuCores").path("value").doubleValue());
	}

	private static Map<String, String> skus(AuditedRun run) {
		Map<String, String> skus = new TreeMap<>();
		run.report()
			.path("sizing")
			.path("nodes")
			.properties()
			.forEach((part) -> skus.put(part.getKey(), AuditJson.text(part.getValue().path("sku"))));
		return skus;
	}

}
