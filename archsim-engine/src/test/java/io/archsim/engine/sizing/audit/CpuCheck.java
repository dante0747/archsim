package io.archsim.engine.sizing.audit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Each auto-sized part's CPU in its run: its p95 busy cores per instance, from the
 * per-bucket {@code cpuBusyCores} series, between 25% and 75% of its vCPU, or a stated
 * reason why not.
 */
final class CpuCheck {

	/** An auto-sized part's p95 CPU is at least this share of its vCPU. */
	private static final double LOW = 0.25;

	/** An auto-sized part's p95 CPU is at most this share of its vCPU. */
	private static final double HIGH = 0.75;

	/**
	 * A part that receives less than this share of the peak the design sends it (a
	 * bottleneck or a queue in front holds it back) is sized for more than it gets: its
	 * low CPU is not a sizing error.
	 */
	private static final double HELD_BACK = 0.75;

	private static final double TOLERANCE = 1e-9;

	private final AuditedRun run;

	private final List<Integer> window;

	private final Set<String> slotBound;

	/**
	 * The check of a run.
	 * @param run the run
	 */
	CpuCheck(AuditedRun run) {
		this.run = run;
		this.window = run.window();
		this.slotBound = slotBound(run);
	}

	/**
	 * Each auto-sized part's check; a part whose CPU the run does not simulate (a load
	 * balancer or a broker: illustrative only) has none.
	 * @return the checks
	 */
	List<AuditCheck> checks() {
		List<AuditCheck> checks = new ArrayList<>();
		for (JsonNode node : this.run.nodes()) {
			JsonNode entry = this.run.sizing(AuditJson.text(node.path("id")));
			if (AuditedRun.autoSized(entry, "cpuCores") && !node.path("timeseries").path("cpuBusyCores").isEmpty()) {
				checks.add(check(node, entry));
			}
		}
		return checks;
	}

	/**
	 * The exact smallest power of two at or above a value, with no rounding of a
	 * logarithm (Python's float {@code log2} rounds a value a few ulps above a power of
	 * two down to it).
	 * @param value a value above 0
	 * @return the power of two
	 */
	static double powerOfTwoAtLeast(double value) {
		double power = Math.scalb(1.0, Math.getExponent(value));
		return (power < value) ? power * 2 : power;
	}

	private AuditCheck check(JsonNode node, JsonNode entry) {
		String id = AuditJson.text(node.path("id"));
		JsonNode busy = node.path("timeseries").path("cpuBusyCores");
		JsonNode config = this.run.config(id);
		List<Double> perInstance = this.window.stream()
			.filter((bucket) -> busy.path(bucket).isNumber())
			.map((bucket) -> busy.path(bucket).doubleValue() / AuditedRun.instances(node, config, bucket))
			.toList();
		double vcpu = entry.path("specs").path("cpuCores").path("value").doubleValue();
		double p95 = AuditedRun.p95(perInstance);
		double util = (vcpu != 0) ? p95 / vcpu : 0.0;
		Optional<String> because = acceptedBecause(node, entry, util, vcpu);
		String sku = entry.path("sku").isString() ? AuditJson.text(entry.path("sku")) : "no type";
		return new AuditCheck(id, "cpu", because.isPresent(),
				String.format(Locale.ROOT, "p95 %.2f busy cores per instance of %s vCPU = %.0f%% (%s; %s)", p95, vcpu,
						util * 100, because.orElse("out of range"), sku));
	}

	// why a part's CPU is acceptable; empty when it is not
	private Optional<String> acceptedBecause(JsonNode node, JsonNode entry, double util, double vcpu) {
		if (LOW <= util && util <= HIGH) {
			return Optional.of("in range");
		}
		if (util < LOW) {
			return acceptedBelowBecause(node, entry, vcpu);
		}
		if (capped(entry)) {
			return Optional.of("capped by its workers or connections");
		}
		boolean bound = util > HIGH && this.slotBound.contains(AuditJson.text(node.path("id")));
		return bound ? Optional.of("the run's bottleneck by its workers or connections, not its CPU")
				: Optional.empty();
	}

	// why a part's CPU below the range is acceptable; empty when it is not
	private Optional<String> acceptedBelowBecause(JsonNode node, JsonNode entry, double vcpu) {
		if (AuditJson.truthy(entry.path("smallest"))) {
			return Optional.of("already on the smallest type its CPU need allows");
		}
		if (capped(entry)) {
			return Optional.of("capped by its workers or connections");
		}
		double need = AuditJson.number(entry.path("need").path("cpuCores"), 0.0);
		if (vcpu > Math.max(1.0, powerOfTwoAtLeast(Math.max(need, TOLERANCE))) + TOLERANCE) {
			return Optional.of("a bigger type for its memory");
		}
		double seen = AuditedRun.p95(AuditedRun.values(node.path("timeseries").path("arrivalRps"), this.window));
		double designed = AuditJson.number(entry.path("peakRps"), 0.0);
		if (designed > 0 && seen < HELD_BACK * designed) {
			return Optional.of(String.format(Locale.ROOT, "its load is held back upstream: p95 %,.0f req/s reached it "
					+ "of the %,.0f the design's peak sends", seen, designed));
		}
		return Optional.empty();
	}

	private static boolean capped(JsonNode entry) {
		return AuditJson.text(entry.path("why").path("cpuCores")).contains("capped at");
	}

	// the parts a bottleneck finding names whose workers or connections limit them
	private static Set<String> slotBound(AuditedRun run) {
		Set<String> bound = new HashSet<>();
		for (JsonNode finding : run.report().path("findings")) {
			String id = AuditJson.text(finding.path("nodeId"));
			JsonNode limit = run.node(id).path("summary").path("capacityLimit");
			if (AuditJson.is(finding.path("kind"), "bottleneck")
					&& (!limit.isString() || AuditJson.is(limit, "slots"))) {
				bound.add(id);
			}
		}
		return bound;
	}

}
